package ru.circumflex.orm

import ORM._
import JDBC._
import java.sql.{ResultSet, PreparedStatement}
import collection.mutable.ListBuffer

// ## Query Commons

/**
 * The most common contract for queries.
 */
trait Query extends SQLable with ParameterizedExpression {
  protected var aliasCounter = 0;

  /**
   * Generate an alias to eliminate duplicates within query.
   */
  protected def nextAlias: String = {
    aliasCounter += 1
    return "this_" + aliasCounter
  }

  /**
   * Set prepared statement parameters of this query starting from specified index.
   * Because `Query` objects can be nested, this method should return the new starting
   * index of prepared statement parameter.
   */
  def setParams(st: PreparedStatement, startIndex: Int): Int = {
    var paramsCounter = startIndex;
    parameters.foreach(p => {
      typeConverter.write(st, p, paramsCounter)
      paramsCounter += 1
    })
    return paramsCounter
  }

  override def toString = toSql
}

// ## SQL Queries

/**
 * A conrtact for SQL queries (data-retrieval). Specified `projection`
 * will be rendered in `SELECT` clause and will be used to read `ResultSet`.
 */
abstract class SQLQuery[T](val projection: Projection[T]) extends Query {

  /**
   * The `SELECT` clause of query. In normal circumstances this list should
   * only consist of single `projection` element; but if `GROUP_BY` clause
   * specifies projections that are not part of `projection`, than, they
   * are added here explicitly.
   */
  def projections: Seq[Projection[_]] = List(projection)

  /**
   * Make sure that projections with alias `this` are assigned query-unique alias.
   */
  protected def ensureProjectionAlias[T](projection: Projection[T]): Unit =
    projection match {
      case p: AtomicProjection[_] if (p.alias == "this") => p.as(nextAlias)
      case p: CompositeProjection[_] =>
        p.subProjections.foreach(ensureProjectionAlias(_))
      case _ =>
    }

  ensureProjectionAlias(projection)

  // ### Data Retrieval Stuff

  /**
   * Execute a query, open a JDBC `ResultSet` and executes specified `actions`.
   */
  def resultSet[A](actions: ResultSet => A): A = transactionManager.sql(toSql)(st => {
    sqlLog.debug(toSql)
    setParams(st, 1)
    auto(st.executeQuery)(actions)
  })

  // ### Executors

  /**
   * Use the query projection to read 
   */
  def read(rs: ResultSet): T = projection.read(rs)

  /**
   * Execute a query and return `Seq[T]`, where `T` is designated by query projection.
   */
  def list(): Seq[T] = resultSet(rs => {
    val result = new ListBuffer[T]()
    while (rs.next)
      result += read(rs)
    return result
  })

  /**
   * Execute a query and return a unique result.
   *
   * An exception is thrown if result set yields more than one row.
   */
  def unique(): Option[T] = resultSet(rs => {
    if (!rs.next) return None
    else if (rs.isLast) return Some(read(rs))
    else throw new ORMException("Unique result expected, but multiple rows found.")
  })

  // ### Miscellaneous

  def sqlSelect: String

  def toSql = sqlSelect

}

// ## Native SQL

class NativeSQLQuery[T](projection: Projection[T],
                        expression: ParameterizedExpression)
    extends SQLQuery[T](projection) {
  def parameters = expression.parameters
  def sqlSelect = expression.toSql.replaceAll("\\{\\*\\}", projection.toSql)
}

// ## Subselect

/**
 * A subset of `SELECT` query -- the one that can participate in subqueries,
 * it does not support `ORDER BY`, `LIMIT` and `OFFSET` clauses.
 */
class Subselect[T](projection: Projection[T])
    extends SQLQuery[T](projection) {

  // ### Commons

  protected var _auxProjections: Seq[Projection[_]] = Nil
  protected var _relations: Seq[RelationNode[_]] = Nil
  protected var _where: Predicate = EmptyPredicate
  protected var _having: Predicate = EmptyPredicate
  protected var _groupBy: Seq[Projection[_]] = Nil
  protected var _setOps: Seq[Pair[SetOperation, SQLQuery[T]]] = Nil

  /**
   * Query parameters.
   */
  def parameters: Seq[Any] = _where.parameters ++
      _having.parameters ++
      _setOps.flatMap(p => p._2.parameters)

  /**
   * Queries combined with this subselect using specific set operation
   * (in pair, `SetOperation -> Subselect`),
   */
  def setOps = _setOps

  /**
   * The `SELECT` clause of query.
   */
  override def projections = List(projection) ++ _auxProjections

  // ### FROM clause

  def from = _relations

  /**
   * Applies specified `nodes` as this query's `FROM` clause.
   * All nodes with `this` alias are assigned query-unique alias.
   */
  def from(nodes: RelationNode[_]*): this.type = {
    this._relations = nodes.toList
    from.foreach(ensureNodeAlias(_))
    return this
  }
  def FROM(nodes: RelationNode[_]*): this.type = from(nodes: _*)

  protected def ensureNodeAlias(node: RelationNode[_]): RelationNode[_] =
    node match {
      case j: JoinNode[_, _] =>
        ensureNodeAlias(j.left)
        ensureNodeAlias(j.right)
        j
      case n: RelationNode[_] if (n.alias == "this") => node.as(nextAlias)
      case n => n
    }

  // ### WHERE clause

  def where: Predicate = this._where

  def where(predicate: Predicate): this.type = {
    this._where = predicate
    return this
  }
  def WHERE(predicate: Predicate): this.type = where(predicate)

  /**
   * Use specified `expression` as the `WHERE` clause of this query
   * with specified named `params`.
   */
  def where(expression: String, params: Pair[String,Any]*): this.type =
    where(prepareExpr(expression, params: _*))
  def WHERE(expression: String, params: Pair[String,Any]*): this.type =
    where(expression, params: _*)

  // ### HAVING clause

  def having: Predicate = this._having

  def having(predicate: Predicate): this.type = {
    this._having = predicate
    return this
  }
  def HAVING(predicate: Predicate): this.type = having(predicate)

  /**
   * Use specified `expression` as the `HAVING` clause of this query
   * with specified named `params`.
   */
  def having(expression: String, params: Pair[String,Any]*): this.type =
    having(prepareExpr(expression, params: _*))
  def HAVING(expression: String, params: Pair[String,Any]*): this.type =
    having(expression, params: _*)

  // ### GROUP BY clause

  def groupBy: Seq[Projection[_]] = _groupBy

  def groupBy(proj: Projection[_]*): this.type = {
    proj.toList.foreach(p => addGroupByProjection(p))
    return this
  }
  def GROUP_BY(proj: Projection[_]*): this.type = groupBy(proj: _*)

  protected def addGroupByProjection(proj: Projection[_]): Unit =
    findProjection(projection, p => p.equals(proj)) match {
      case None =>
        ensureProjectionAlias(proj)
        this._auxProjections ++= List(proj)
        this._groupBy ++= List(proj)
      case Some(p) => this._groupBy ++= List(p)
    }

  /**
   * Search deeply for a projection that matches specified `predicate` function.
   */
  protected def findProjection(projection: Projection[_],
                               predicate: Projection[_] => Boolean): Option[Projection[_]] =
    if (predicate(projection)) return Some(projection)
    else projection match {
      case p: CompositeProjection[_] =>
        return p.subProjections.find(predicate)
      case _ => return None
    }

  // ### Set Operations

  protected def addSetOp(op: SetOperation, sql: SQLQuery[T]): this.type = {
    _setOps ++= List(op -> sql)
    return this
  }

  def union(sql: SQLQuery[T]): this.type =
    addSetOp(OP_UNION, sql)
  def UNION(sql: SQLQuery[T]): this.type = union(sql)

  def unionAll(sql: SQLQuery[T]): this.type =
    addSetOp(OP_UNION_ALL, sql)
  def UNION_ALL(sql: SQLQuery[T]): this.type =
    unionAll(sql)

  def except(sql: SQLQuery[T]): this.type =
    addSetOp(OP_EXCEPT, sql)
  def EXCEPT(sql: SQLQuery[T]): this.type =
    except(sql)

  def exceptAll(sql: SQLQuery[T]): this.type =
    addSetOp(OP_EXCEPT_ALL, sql)
  def EXCEPT_ALL(sql: SQLQuery[T]): this.type =
    exceptAll(sql)

  def intersect(sql: SQLQuery[T]): this.type =
    addSetOp(OP_INTERSECT, sql)
  def INTERSECT(sql: SQLQuery[T]): this.type =
    intersect(sql)

  def intersectAll(sql: SQLQuery[T]): this.type =
    addSetOp(OP_INTERSECT_ALL, sql)
  def INTERSECT_ALL(sql: SQLQuery[T]): this.type =
    intersectAll(sql)

  // ### Miscellaneous

  override def sqlSelect = dialect.subselect(this)

}

// ## Full Select

/**
 * A full-fledged `SELECT` query.
 */
class Select[T](projection: Projection[T]) extends Subselect[T](projection) {

  protected var _orders: Seq[Order] = Nil
  protected var _limit: Int = -1
  protected var _offset: Int = 0

  override def parameters: Seq[Any] =
    super.parameters ++ _orders.flatMap(_.parameters)

  // ### ORDER BY clause

  def orderBy = _orders
  def orderBy(order: Order*): this.type = {
    this._orders ++= order.toList
    return this
  }
  def ORDER_BY(order: Order*): this.type =
    orderBy(order: _*)

  // ### LIMIT and OFFSET clauses

  def limit = this._limit
  def limit(value: Int): this.type = {
    _limit = value
    return this
  }
  def LIMIT(value: Int): this.type = limit(value)

  def offset = this._offset
  def offset(value: Int): this.type = {
    _offset = value
    return this
  }
  def OFFSET(value: Int): this.type = offset(value)

  // ### Miscellaneous

  override def toSql = dialect.select(this)

}

// ## DML Queries

/**
 * A conrtact for DML queries (data-manipulation).
 */
trait DMLQuery extends Query {

  /**
   * Execute a query and return the number of affected rows.
   */
  def execute: Int = transactionManager.dml(conn => {
    val sql = toSql
    sqlLog.debug(sql)
    auto(conn.prepareStatement(sql))(st => {
      setParams(st, 1)
      st.executeUpdate
    })
  })
}

// ## Native DML

class NativeDMLQuery(expression: ParameterizedExpression) extends DMLQuery {
  def parameters = expression.parameters
  def toSql = expression.toSql
}

// ## INSERT-SELECT query

/**
 * Functionality for INSERT-SELECT query. Data extracted using specified `query`
 * and inserted into specified `relation`.
 *
 * The projections of `query` must match the columns of target `relation`.
 */
class InsertSelect[R <: Record[R]](val relation: Relation[R],
                                   val query: SQLQuery[_])
    extends DMLQuery {
  if (relation.readOnly_?)
    throw new ORMException("The relation " + relation.qualifiedName + " is read-only.")
  def parameters = query.parameters
  def toSql: String = dialect.insertSelect(this)
}

// ## DELETE query

/**
 * Functionality for DELETE query.
 */
class Delete[R <: Record[R]](val node: RelationNode[R])
    extends DMLQuery {
  val relation = node.relation
  if (relation.readOnly_?)
    throw new ORMException("The relation " + relation.qualifiedName + " is read-only.")

  // ### WHERE clause

  protected var _where: Predicate = EmptyPredicate
  def where: Predicate = this._where
  def where(predicate: Predicate): this.type = {
    this._where = predicate
    return this
  }
  def WHERE(predicate: Predicate): this.type = where(predicate)

  // ### Miscellaneous
  def parameters = _where.parameters
  def toSql: String = dialect.delete(this)
}

// ## UPDATE query

/**
 * Functionality for UPDATE query.
 */
class Update[R <: Record[R]](val relation: Relation[R])
    extends DMLQuery {
  if (relation.readOnly_?)
    throw new ORMException("The relation " + relation.qualifiedName + " is read-only.")

  // ### SET clause

  private var _setClause: Seq[Pair[Field[_], Any]] = Nil
  def setClause = _setClause
  def set[T](field: Field[T], value: T): this.type = {
    _setClause ++= List(field -> value)
    return this
  }
  def SET[T](field: Field[T], value: T): this.type = set(field, value)
  def set[P <: Record[P]](association: Association[R, P], value: P): this.type =
    set(association.field, value.id.get)
  def SET[P <: Record[P]](association: Association[R, P], value: P): this.type =
    set(association, value)
  def setNull[T](field: Field[T]): this.type = set(field, null.asInstanceOf[T])
  def SET_NULL[T](field: Field[T]): this.type = setNull(field)
  def setNull[P <: Record[P]](association: Association[R, P]): this.type =
    setNull(association.field)
  def SET_NULL[P <: Record[P]](association: Association[R, P]): this.type =
    setNull(association)

  // ### WHERE clause

  protected var _where: Predicate = EmptyPredicate
  def where: Predicate = this._where
  def where(predicate: Predicate): this.type = {
    this._where = predicate
    return this
  }
  def WHERE(predicate: Predicate): this.type = where(predicate)

  // ### Miscellaneous

  def parameters = _setClause.map(_._2) ++ _where.parameters
  def toSql: String = dialect.update(this)

}



