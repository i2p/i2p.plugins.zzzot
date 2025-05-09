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
<%
    boolean udp = ZzzOTController.isUDPEnabled();
    if (udp) {
%>
        <b>Total Announce Rate:</b>
<%
    } else {
%>
        <b>Announce Rate:</b>
<%
    }
%>
<%=String.format(java.util.Locale.US, "%.1f", ZzzOTController.getAnnounceRate())%> / minute<br>
<b>Announce Interval:</b> <%=torrents.getInterval() / 60%> minutes<br>
<%
    String host = ZzzOTController.b32();
    if (host != null) {
            %><b>Announce URL:</b> <a href="http://<%=host%>/a">http://<%=host%>/a</a><br><%
    }
%>
<b>UDP Announce Support:</b> <%=udp ? "yes" : "no"%><br>
<%
    if (udp) {
%>
        <b>UDP Announce Rate:</b> <%=String.format(java.util.Locale.US, "%.1f", ZzzOTController.getUDPAnnounceRate())%> / minute<br>
        <b>UDP Connection Lifetime:</b> <%=torrents.getUDPLifetime() / 60%> minutes<br>
<%
        if (host != null) {
            int port = ZzzOTController.udpPort();
%>
            <b>UDP Announce URL:</b> <a href="udp://<%=host%>:<%=port%>/"</a>udp://<%=host%>:<%=port%>/</a><br>
<%
        }
    }
%>
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
