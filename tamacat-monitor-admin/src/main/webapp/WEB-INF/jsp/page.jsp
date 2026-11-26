<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="<c:out value='${locale}'/>">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title><c:out value="${pageTitle}"/> - <c:out value="${appTitle}"/></title>
<c:url var="cssUrl" value="/static/js/${page}.css"><c:param name="v" value="${version}"/></c:url>
<c:url var="jsUrl" value="/static/js/${page}.js"><c:param name="v" value="${version}"/></c:url>
<link rel="stylesheet" href="<c:out value='${cssUrl}'/>">
<script type="module" src="<c:out value='${jsUrl}'/>"></script>
</head>
<body>
<div id="root"></div>
<noscript><c:out value="${noscript}"/></noscript>
<%-- bootstrapJson is JSON with <, >, & escaped (PageServlet.scriptSafe); it is data, not executed --%>
<script id="tmon-bootstrap" type="application/json">${bootstrapJson}</script>
</body>
</html>
