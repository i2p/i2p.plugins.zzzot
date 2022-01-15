<%@page import="net.i2p.zzzot.ZzzOTController,net.i2p.zzzot.Torrents" %>
<%@page trimDirectiveWhitespaces="true"%>
<!DOCTYPE HTML>
<html>
<head>
<meta charset="UTF-8">
<noscript><meta http-equiv="refresh" content="300;url=."></noscript>
<title><%=ZzzOTController.getSiteName()%> OPENTRACKER | STATS</title>
<link href="/tracker.css" rel="stylesheet" type="text/css">
<link rel="icon" type="image/png" href="/favicon.png">
</head>
<body id="stats">
<div id="container">
<div id="panel">
<a href="/" title="Return to home page" alt="Return to home page"><span id="sitename"><%=ZzzOTController.getSiteName()%></span></a><hr>
<%
    Torrents torrents = ZzzOTController.getTorrents();
    if (torrents != null) {
%>
<p id="totals">
<b>Torrents:</b> <%=torrents.size()%><br>
<b>Peers:</b> <%=torrents.countPeers()%><br>
</p>
<%
    } else {
%>
<p id="initializing"><b><i>Initializing OpenTracker&hellip;</i></b></p>
<%
    }
%>
<%
    boolean showfooter = ZzzOTController.shouldShowFooter();
    if (showfooter) {
%>
<span id="footer" class="version"><%=ZzzOTController.footerText()%></span>
<%
    }
%>
</div>
</div>
<script type="text/javascript">
  setInterval(function() {
    var xhr = new XMLHttpRequest();
    xhr.open('GET', '/tracker/?' + new Date().getTime(), true);
    xhr.responseType = "text";
    xhr.onreadystatechange = function () {
      if (xhr.readyState==4 && xhr.status==200) {
        document.getElementById("stats").innerHTML = xhr.responseText;
      }
    }
    xhr.send();
<%
    if (torrents != null) {
%>
  }, 60000);
<%
    } else {
%>
  }, 15000);
<%
    }
%>
</script>
</body>
</html>
