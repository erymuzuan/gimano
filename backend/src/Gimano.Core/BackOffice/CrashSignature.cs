using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

namespace Gimano.Core.BackOffice;

/// <summary>
/// Groups crash reports: sha256 of platform + exception type + the top 5 app frames, with line numbers, addresses and
/// lambda/coroutine suffixes removed so that the same bug keeps its signature across builds.
/// </summary>
public static partial class CrashSignature
{
    public const int AppFrameCount = 5;

    /// <summary>Package / module prefixes that mark a frame as ours.</summary>
    private static readonly string[] AppMarkers = ["my.gimano.", "Gimano", "gimano"];

    public static string Compute(string platform, string exception, string stack)
    {
        var frames = AppFrames(stack).Take(AppFrameCount).ToList();
        // A crash entirely inside framework code still groups by its first frames.
        if (frames.Count == 0) frames = Frames(stack).Take(AppFrameCount).Select(Normalise).ToList();
        var text = $"{platform.Trim().ToLowerInvariant()}\n{exception.Trim()}\n{string.Join('\n', frames)}";
        return Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(text)));
    }

    /// <summary>The first app frame, for list titles ("NavigationSession.reroute(NavigationSession.kt:142)").</summary>
    public static string? TopFrame(string stack) =>
        Frames(stack).FirstOrDefault(IsAppFrame) is { } f ? Truncate(f.Trim(), 300) : null;

    internal static IEnumerable<string> AppFrames(string stack) => Frames(stack).Where(IsAppFrame).Select(Normalise);

    private static IEnumerable<string> Frames(string stack) =>
        stack.Split('\n').Select(l => l.Trim()).Where(l => l.StartsWith("at ", StringComparison.Ordinal) || IosFrame().IsMatch(l));

    private static bool IsAppFrame(string frame) => AppMarkers.Any(m => frame.Contains(m, StringComparison.Ordinal));

    internal static string Normalise(string frame)
    {
        var f = frame.Trim();
        if (f.StartsWith("at ", StringComparison.Ordinal)) f = f[3..];
        f = IosFrame().Replace(f, "$1 $2");            // "3  Gimano  0x0000000100a1c2 closure #1 in X + 120" → "Gimano closure #1 in X + 120"
        f = LineNumber().Replace(f, "");                // ":142)" → ")"
        f = HexAddress().Replace(f, "");                // 0x… addresses
        f = OffsetSuffix().Replace(f, "");              // " + 120"
        f = SyntheticSuffix().Replace(f, "");           // $lambda$3, $1, $invokeSuspend$2
        return WhiteSpace().Replace(f, " ").Trim();
    }

    private static string Truncate(string s, int max) => s.Length <= max ? s : s[..max];

    [GeneratedRegex(@"^\d+\s+(\S+)\s+0x[0-9a-fA-F]+\s+(.*)$")] private static partial Regex IosFrame();
    [GeneratedRegex(@":\d+(?=\))")] private static partial Regex LineNumber();
    [GeneratedRegex(@"0x[0-9a-fA-F]+")] private static partial Regex HexAddress();
    [GeneratedRegex(@"\s\+\s\d+$")] private static partial Regex OffsetSuffix();
    [GeneratedRegex(@"\$(lambda\$)?\d+|\$\$Lambda\$[^.(]*")] private static partial Regex SyntheticSuffix();
    [GeneratedRegex(@"\s+")] private static partial Regex WhiteSpace();
}
