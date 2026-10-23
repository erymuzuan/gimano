using Gimano.Api.Services;
using Microsoft.AspNetCore.Diagnostics;
using Microsoft.AspNetCore.Mvc;

namespace Gimano.Api;

/// <summary>Maps domain exceptions to RFC 7807 problem responses.</summary>
public sealed class ApiExceptionHandler(IProblemDetailsService problems, ILogger<ApiExceptionHandler> logger) : IExceptionHandler
{
    public async ValueTask<bool> TryHandleAsync(HttpContext http, Exception exception, CancellationToken ct)
    {
        if (exception is ValidationException ve)
        {
            http.Response.StatusCode = StatusCodes.Status400BadRequest;
            return await problems.TryWriteAsync(new ProblemDetailsContext
            {
                HttpContext = http,
                ProblemDetails = new HttpValidationProblemDetails(new Dictionary<string, string[]> { [ve.Field] = [ve.Message] })
                    { Status = 400, Title = "One or more validation errors occurred." },
            });
        }
        if (exception is DuplicatePlaceException dup)
        {
            http.Response.StatusCode = StatusCodes.Status409Conflict;
            var pd = new ProblemDetails { Status = 409, Title = "Similar place nearby", Detail = dup.Message };
            pd.Extensions["existing"] = dup.Existing;
            return await problems.TryWriteAsync(new ProblemDetailsContext { HttpContext = http, ProblemDetails = pd });
        }

        var (status, title) = exception switch
        {
            RateLimitedException => (StatusCodes.Status429TooManyRequests, "Too many requests"),
            ForbiddenException => (StatusCodes.Status403Forbidden, "Forbidden"),
            UnsupportedMediaException => (StatusCodes.Status415UnsupportedMediaType, "Unsupported image format"),
            PayloadTooLargeException => (StatusCodes.Status413PayloadTooLarge, "Too large"),
            RoutingUnavailableException => (StatusCodes.Status503ServiceUnavailable, "Routing unavailable"),
            NoRouteException => (StatusCodes.Status422UnprocessableEntity, "No route found"),
            NotFoundException => (StatusCodes.Status404NotFound, "Not found"),
            ConflictException => (StatusCodes.Status409Conflict, "Conflict"),
            Core.BackOffice.ScopeException => (StatusCodes.Status403Forbidden, "Forbidden"),
            KeyNotFoundException => (StatusCodes.Status404NotFound, "Not found"),
            ArgumentException => (StatusCodes.Status400BadRequest, "Bad request"),
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
