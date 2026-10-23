using Gimano.Api.Endpoints;
using Gimano.Api.Live;
using Microsoft.AspNetCore.Http;
using R = Gimano.Api.Live.ShareStore.Result;

namespace Gimano.Tests;

public class ShareStoreTests
{
    private sealed class FixedClock : TimeProvider
    {
        public DateTimeOffset Now = new(2026, 10, 23, 8, 0, 0, TimeSpan.Zero);
        public override DateTimeOffset GetUtcNow() => Now;
    }

    private static readonly byte[] Nonce = new byte[24];
    private static readonly byte[] Cipher = new byte[100];

    [Fact]
    public void Lifecycle_create_put_get_delete_then_410()
    {
        var clock = new FixedClock();
        var store = new ShareStore(clock);
        var (r, c) = store.Create(Guid.NewGuid(), 60);
        Assert.Equal(R.Ok, r);
        Assert.Equal(clock.Now.AddMinutes(60), c!.ExpiresAt);

        var (r0, empty) = store.Get(c.ShareId);
        Assert.Equal(R.Ok, r0);
        Assert.Null(empty!.Ciphertext);

        Assert.Equal(R.Ok, store.Put(c.ShareId, c.WriteToken, Nonce, Cipher));
        var (r1, blob) = store.Get(c.ShareId);
        Assert.Equal(R.Ok, r1);
        Assert.Equal(100, blob!.Ciphertext!.Length);
        Assert.Equal(clock.Now, blob.UpdatedAt);

        Assert.Equal(R.Ok, store.Delete(c.ShareId, c.WriteToken));
        Assert.Equal(R.Gone, store.Get(c.ShareId).Item1);
        Assert.Equal(R.Gone, store.Put(c.ShareId, c.WriteToken, Nonce, Cipher));
        Assert.Equal(R.NotFound, store.Get("nope").Item1);
    }

    [Fact]
    public void Ids_and_tokens_are_128_and_256_bit_random()
    {
        var store = new ShareStore(new FixedClock());
        var (_, a) = store.Create(Guid.NewGuid(), 15);
        var (_, b) = store.Create(Guid.NewGuid(), 15);
        Assert.Equal(22, a!.ShareId.Length);    // 16 bytes base64url
        Assert.Equal(43, a.WriteToken.Length);  // 32 bytes base64url
        Assert.NotEqual(a.ShareId, b!.ShareId);
        Assert.DoesNotContain('+', a.WriteToken + a.ShareId);
        Assert.DoesNotContain('/', a.WriteToken + a.ShareId);
    }

    [Fact]
    public void Writes_need_the_write_token()
    {
        var store = new ShareStore(new FixedClock());
        var (_, c) = store.Create(Guid.NewGuid(), 30);
        Assert.Equal(R.Unauthorized, store.Put(c!.ShareId, null, Nonce, Cipher));
        Assert.Equal(R.Unauthorized, store.Put(c.ShareId, c.WriteToken + "x", Nonce, Cipher));
        Assert.Equal(R.Unauthorized, store.Delete(c.ShareId, "wrong"));
        Assert.Equal(R.NotFound, store.Put("unknown", c.WriteToken, Nonce, Cipher));
    }

    [Fact]
    public void Expires_at_ttl_and_drops_data()
    {
        var clock = new FixedClock();
        var store = new ShareStore(clock);
        var (_, c) = store.Create(Guid.NewGuid(), 15);
        store.Put(c!.ShareId, c.WriteToken, Nonce, Cipher);
        clock.Now = clock.Now.AddMinutes(15);
        Assert.Equal(R.Gone, store.Get(c.ShareId).Item1);
        // Tombstone kept for a while (410), then forgotten (404).
        clock.Now = clock.Now.AddHours(13);
        store.Sweep();
        Assert.Equal(R.NotFound, store.Get(c.ShareId).Item1);
    }

    [Theory]
    [InlineData(14, R.Invalid)]
    [InlineData(15, R.Ok)]
    [InlineData(720, R.Ok)]
    [InlineData(721, R.Invalid)]
    public void Duration_is_15_minutes_to_12_hours(int minutes, R expected) =>
        Assert.Equal(expected, new ShareStore(new FixedClock()).Create(Guid.NewGuid(), minutes).Item1);

    [Fact]
    public void At_most_three_active_shares_per_user()
    {
        var store = new ShareStore(new FixedClock());
        var u = Guid.NewGuid();
        var ids = Enumerable.Range(0, 3).Select(_ => store.Create(u, 60).Item2!).ToList();
        Assert.Equal(R.TooMany, store.Create(u, 60).Item1);
        Assert.Equal(R.Ok, store.Create(Guid.NewGuid(), 60).Item1);   // other users unaffected
        store.Delete(ids[0].ShareId, ids[0].WriteToken);
        Assert.Equal(R.Ok, store.Create(u, 60).Item1);                 // ending one frees a slot
    }

    [Fact]
    public void Blob_size_and_shape_limits()
    {
        var store = new ShareStore(new FixedClock());
        var (_, c) = store.Create(Guid.NewGuid(), 30);
        Assert.Equal(R.Ok, store.Put(c!.ShareId, c.WriteToken, Nonce, new byte[1024]));
        Assert.Equal(R.TooLarge, store.Put(c.ShareId, c.WriteToken, Nonce, new byte[1025]));
        Assert.Equal(R.Invalid, store.Put(c.ShareId, c.WriteToken, new byte[12], Cipher));   // not an XChaCha nonce
        Assert.Equal(R.Invalid, store.Put(c.ShareId, c.WriteToken, Nonce, new byte[16]));    // tag only, no plaintext
    }

    [Theory]
    [InlineData("Share abc", "abc")]
    [InlineData("Bearer abc", null)]
    [InlineData("", null)]
    public void Reads_share_authorization_scheme(string header, string? token)
    {
        var ctx = new DefaultHttpContext();
        if (header.Length > 0) ctx.Request.Headers.Authorization = header;
        Assert.Equal(token, ShareEndpoints.Token(ctx));
    }
}
