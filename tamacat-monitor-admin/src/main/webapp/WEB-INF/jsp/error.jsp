<%@ page contentType="text/html; charset=UTF-8" isErrorPage="true" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>Error</title>
</head>
<body>
<main>
  <h1>Error <c:out value="${requestScope['jakarta.servlet.error.status_code']}"/></h1>
  <p><a href="<c:url value='/'/>">Home</a></p>
</main>
</body>
</html>
