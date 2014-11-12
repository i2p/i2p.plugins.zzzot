<%@page import="net.i2p.zzzot.ZzzOTController,net.i2p.zzzot.Torrents" %>
<html>
<head>
<title>ZzzOT</title>
</head><body style="background-color: #000; color: #c30; font-size: 400%;">
<p>
zzzot
<p>
<%
    Torrents torrents = ZzzOTController.getTorrents();
    if (torrents != null) {
%>
<table cellspacing="8">
<tr><td>Torrents:<td align="right"><%=torrents.size()%>
<tr><td>Peers:<td align="right"><%=torrents.countPeers()%>
</table>
<%
    } else {
%>
ZzzOT is not running
<%
    }
%>
</body>
</html>
