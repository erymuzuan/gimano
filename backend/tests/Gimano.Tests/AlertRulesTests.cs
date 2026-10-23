using Gimano.Core.Alerts;
using Gimano.Core.Contracts;
using Gimano.Core.Geo;

namespace Gimano.Tests;

public class AlertRulesTests
{
    private static readonly DateTimeOffset T0 = new(2026, 10, 23, 8, 0, 0, TimeSpan.Zero);

    [Theory]
    [InlineData(ReportType.Police, 30)]
    [InlineData(ReportType.Accident, 60)]
    [InlineData(ReportType.Hazard, 60)]
    [InlineData(ReportType.Traffic, 20)]
    [InlineData(ReportType.Closure, 24 * 60)]
    [InlineData(ReportType.Weather, 120)]
    public void Base_ttl_matches_architecture_doc(ReportType type, int minutes)
    {
        Assert.Equal(TimeSpan.FromMinutes(minutes), AlertRules.BaseTtl(type));
        Assert.Equal(T0.AddMinutes(minutes), AlertRules.InitialExpiry(type, T0));
    }

    [Fact]
    public void Still_there_extends_by_half_the_base_ttl()
    {
        var expires = AlertRules.InitialExpiry(ReportType.Police, T0); // +30
        var extended = AlertRules.ExtendOnStillThere(ReportType.Police, T0, expires, T0.AddMinutes(10));
        Assert.Equal(T0.AddMinutes(45), extended);
    }

    [Fact]
    public void Still_there_is_capped_at_three_times_base()
    {
        var expires = AlertRules.InitialExpiry(ReportType.Police, T0);
        for (var i = 0; i < 10; i++) expires = AlertRules.ExtendOnStillThere(ReportType.Police, T0, expires, T0.AddMinutes(5));
        Assert.Equal(T0.AddMinutes(90), expires);
    }

    [Fact]
    public void Still_there_after_expiry_extends_from_now()
    {
        var extended = AlertRules.ExtendOnStillThere(ReportType.Accident, T0, T0.AddMinutes(60), T0.AddMinutes(70));
        Assert.Equal(T0.AddMinutes(100), extended);
    }

    [Fact]
    public void Two_not_there_votes_expire_the_report()
    {
        Assert.False(AlertRules.ShouldExpireOnNotThere([1.0]));
        Assert.True(AlertRules.ShouldExpireOnNotThere([1.0, 1.0]));
    }

    [Fact]
    public void Reputation_weights_not_there_votes()
    {
        Assert.True(AlertRules.ShouldExpireOnNotThere([AlertRules.VoteWeight(2.0)]));       // one trusted user is enough
        Assert.False(AlertRules.ShouldExpireOnNotThere([AlertRules.VoteWeight(0.5), AlertRules.VoteWeight(0.5)]));
        Assert.Equal(0.5, AlertRules.VoteWeight(0.1));
        Assert.Equal(2.0, AlertRules.VoteWeight(9));
    }

    [Fact]
    public void Duplicate_requires_same_type_within_150m_and_similar_heading()
    {
        var a = new GeoPoint(3.1000, 101.6000);
        var near = new GeoPoint(3.1010, 101.6000);   // ~111 m
        var far = new GeoPoint(3.1020, 101.6000);    // ~222 m

        Assert.True(AlertRules.IsDuplicate(ReportType.Police, near, 90, ReportType.Police, a, 100));
        Assert.False(AlertRules.IsDuplicate(ReportType.Police, far, 90, ReportType.Police, a, 90));
        Assert.False(AlertRules.IsDuplicate(ReportType.Hazard, near, 90, ReportType.Police, a, 90));
        Assert.False(AlertRules.IsDuplicate(ReportType.Police, near, 90, ReportType.Police, a, 270));
        Assert.True(AlertRules.IsDuplicate(ReportType.Police, near, 350, ReportType.Police, a, 10)); // wraps around north
        Assert.True(AlertRules.IsDuplicate(ReportType.Police, near, null, ReportType.Police, a, 270));
    }

    [Theory]
    [InlineData(ReportType.Hazard, "pothole", true)]
    [InlineData(ReportType.Hazard, "speed_bump", false)]
    [InlineData(ReportType.Police, "roadblock", true)]
    [InlineData(ReportType.Traffic, "standstill", true)]
    [InlineData(ReportType.Weather, "haze", true)]
    [InlineData(ReportType.Closure, "anything", true)]
    [InlineData(ReportType.Accident, null, true)]
    public void Validates_subtypes(ReportType type, string? subtype, bool valid) =>
        Assert.Equal(valid, AlertRules.ValidateSubtype(type, subtype) is null);
}
