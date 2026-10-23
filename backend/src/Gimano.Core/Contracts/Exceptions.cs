namespace Gimano.Core.Contracts;

// Domain exceptions shared by the API (mapped to problem+json by ApiExceptionHandler) and the back office.

/// <summary>400 with the offending field.</summary>
public sealed class ValidationException(string field, string message) : Exception(message)
{
    public string Field { get; } = field;
}

/// <summary>404.</summary>
public sealed class NotFoundException(string message) : Exception(message);

/// <summary>409.</summary>
public sealed class ConflictException(string message) : Exception(message);

/// <summary>403: authenticated, but not allowed.</summary>
public sealed class ForbiddenException(string message) : Exception(message);
