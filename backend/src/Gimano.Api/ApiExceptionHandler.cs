using Gimano.Api.Services;
using Microsoft.AspNetCore.Diagnostics;
using Microsoft.AspNetCore.Mvc;

namespace Gimano.Api;

/// <summary>Maps domain exceptions to RFC 7807 problem responses.</summary>
public sealed class ApiExceptionHandler(IProblemDetailsService problems, ILogger<ApiExceptionHandler> logger) : IExceptionHandler
{
    public async ValueTask<bool> TryHandleAsync(HttpContext http, Exception exception, CancellationToken ct)
    {
        var (status, title) = exception switch
        {
            RoutingUnavailableException => (StatusCodes.Status503ServiceUnavailable, "Routing unavailable"),
            NoRouteException => (StatusCodes.Status422UnprocessableEntity, "No route found"),
            NotFoundException => (StatusCodes.Status404NotFound, "Not found"),
            ConflictException => (StatusCodes.Status409Conflict, "Conflict"),
            UnauthorizedAccessException => (StatusCodes.Status401Unauthorized, "Unauthorized"),
            BadHttpRequestException b => (b.StatusCode, "Bad request"),
            _ => (StatusCodes.Status500InternalServerError, "Unexpected error"),
        };
        if (status >= 500 && exception is not RoutingUnavailableException)
            logger.LogError(exception, "Unhandled exception for {Method} {Path}", http.Request.Method, http.Request.Path);

        http.Response.StatusCode = status;
        if (status == StatusCodes.Status503ServiceUnavailable) http.Response.Headers.RetryAfter = "30";
        return await problems.TryWriteAsync(new ProblemDetailsContext
        {
            HttpContext = http,
            Exception = exception,
            ProblemDetails = new ProblemDetails
            {
                Status = status,
                Title = title,
                Detail = status == 500 ? "An unexpected error occurred." : exception.Message,
            },
        });
    }
}
