[#ftl]
[#list lines as line]${line}[/#list]
[#if status.currentJob??]
<div id="process-placeholder"
     class="centered">
  <img src="http://cdn.savant.pro/img/ui/loading.gif"/>
  <script type="text/javascript">
    var from = ${from!(lines?size)};
    // Cancel a monitor which is already scheduled
    if (window._processMonitor)
      clearTimeout(window._processMonitor);
    // Schedule updates
    window._processMonitor = setTimeout(function() {
      $.get("/cluster/${cluster.id}/~job-progress",
          { from: from },
          function(data) {
            $("#process-placeholder").replaceWith(data);
            $("#job-output").scrollTop(100000);
          });
    }, 1000);
  </script>
</div>
[#else]
<div class="centered">
  <a href="javascript:location.reload();"
     class="btn primary inverse">
    <img class="glyph"
         src="http://cdn.savant.pro/img/glyph/32-inv/refresh.png"/>
    <span>${msg['refresh']}</span>
  </a>
</div>
[/#if]