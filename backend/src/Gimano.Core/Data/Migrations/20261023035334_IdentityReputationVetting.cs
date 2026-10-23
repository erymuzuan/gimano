using System;
using Microsoft.EntityFrameworkCore.Migrations;
using Npgsql.EntityFrameworkCore.PostgreSQL.Metadata;

#nullable disable

namespace Gimano.Core.Data.Migrations
{
    /// <inheritdoc />
    public partial class IdentityReputationVetting : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropIndex(
                name: "ix_users_device_id",
                table: "users");

            migrationBuilder.AlterColumn<string>(
                name: "device_id",
                table: "users",
                type: "character varying(200)",
                maxLength: 200,
                nullable: true,
                oldClrType: typeof(string),
                oldType: "character varying(200)",
                oldMaxLength: 200);

            migrationBuilder.AddColumn<DateOnly>(
                name: "last_active_day",
                table: "users",
                type: "date",
                nullable: true);

            migrationBuilder.AddColumn<int>(
                name: "places_accepted",
                table: "users",
                type: "integer",
                nullable: false,
                defaultValue: 0);

            migrationBuilder.AddColumn<int>(
                name: "places_disputed_upheld",
                table: "users",
                type: "integer",
                nullable: false,
                defaultValue: 0);

            migrationBuilder.AddColumn<int>(
                name: "places_rejected",
                table: "users",
                type: "integer",
                nullable: false,
                defaultValue: 0);

            migrationBuilder.AddColumn<DateTimeOffset>(
                name: "suspended_until",
                table: "users",
                type: "timestamp with time zone",
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "tier",
                table: "users",
                type: "character varying(8)",
                maxLength: 8,
                nullable: false,
                defaultValue: "Baharu");

            migrationBuilder.AddColumn<int>(
                name: "verifier_agreed",
                table: "users",
                type: "integer",
                nullable: false,
                defaultValue: 0);

            migrationBuilder.AddColumn<int>(
                name: "verifier_votes",
                table: "users",
                type: "integer",
                nullable: false,
                defaultValue: 0);

            migrationBuilder.AddColumn<DateOnly>(
                name: "votes_day",
                table: "users",
                type: "date",
                nullable: true);

            migrationBuilder.AddColumn<int>(
                name: "votes_today",
                table: "users",
                type: "integer",
                nullable: false,
                defaultValue: 0);

            migrationBuilder.AddColumn<bool>(
                name: "audit_open",
                table: "community_places",
                type: "boolean",
                nullable: false,
                defaultValue: false);

            migrationBuilder.AddColumn<double>(
                name: "required_confirmations",
                table: "community_places",
                type: "double precision",
                nullable: false,
                defaultValue: 3.0); // existing places were added by baharu users

            migrationBuilder.AddColumn<DateTimeOffset>(
                name: "resolved_at",
                table: "community_places",
                type: "timestamp with time zone",
                nullable: true);

            migrationBuilder.AddColumn<bool>(
                name: "rural",
                table: "community_places",
                type: "boolean",
                nullable: false,
                defaultValue: false);

            migrationBuilder.AddColumn<double>(
                name: "weighted_no",
                table: "community_places",
                type: "double precision",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "weighted_yes",
                table: "community_places",
                type: "double precision",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.CreateTable(
                name: "app_attest_keys",
                columns: table => new
                {
                    key_id = table.Column<string>(type: "character varying(100)", maxLength: 100, nullable: false),
                    user_id = table.Column<Guid>(type: "uuid", nullable: false),
                    public_key = table.Column<byte[]>(type: "bytea", nullable: false),
                    counter = table.Column<long>(type: "bigint", nullable: false),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_app_attest_keys", x => x.key_id);
                    table.ForeignKey(
                        name: "fk_app_attest_keys_users_user_id",
                        column: x => x.user_id,
                        principalTable: "users",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "place_disputes",
                columns: table => new
                {
                    id = table.Column<long>(type: "bigint", nullable: false)
                        .Annotation("Npgsql:ValueGenerationStrategy", NpgsqlValueGenerationStrategy.IdentityByDefaultColumn),
                    place_id = table.Column<string>(type: "character varying(40)", maxLength: 40, nullable: false),
                    contributor_id = table.Column<Guid>(type: "uuid", nullable: true),
                    upheld = table.Column<bool>(type: "boolean", nullable: true),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    resolved_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_place_disputes", x => x.id);
                    table.ForeignKey(
                        name: "fk_place_disputes_users_contributor_id",
                        column: x => x.contributor_id,
                        principalTable: "users",
                        principalColumn: "id",
                        onDelete: ReferentialAction.SetNull);
                });

            migrationBuilder.CreateTable(
                name: "place_salts",
                columns: table => new
                {
                    place_id = table.Column<string>(type: "character varying(40)", maxLength: 40, nullable: false),
                    salt = table.Column<byte[]>(type: "bytea", nullable: false),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    resolved_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_place_salts", x => x.place_id);
                });

            migrationBuilder.CreateTable(
                name: "place_votes",
                columns: table => new
                {
                    id = table.Column<long>(type: "bigint", nullable: false)
                        .Annotation("Npgsql:ValueGenerationStrategy", NpgsqlValueGenerationStrategy.IdentityByDefaultColumn),
                    place_id = table.Column<string>(type: "character varying(40)", maxLength: 40, nullable: false),
                    voter_id = table.Column<Guid>(type: "uuid", nullable: true),
                    voter_hash = table.Column<byte[]>(type: "bytea", nullable: true),
                    answer = table.Column<string>(type: "character varying(8)", maxLength: 8, nullable: false),
                    name_correct = table.Column<bool>(type: "boolean", nullable: true),
                    location_correct = table.Column<bool>(type: "boolean", nullable: true),
                    weight = table.Column<double>(type: "double precision", nullable: false),
                    attestation_ok = table.Column<bool>(type: "boolean", nullable: true),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true),
                    day = table.Column<DateOnly>(type: "date", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_place_votes", x => x.id);
                    table.ForeignKey(
                        name: "fk_place_votes_community_places_place_id",
                        column: x => x.place_id,
                        principalTable: "community_places",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                    table.ForeignKey(
                        name: "fk_place_votes_users_voter_id",
                        column: x => x.voter_id,
                        principalTable: "users",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "user_keys",
                columns: table => new
                {
                    id = table.Column<long>(type: "bigint", nullable: false)
                        .Annotation("Npgsql:ValueGenerationStrategy", NpgsqlValueGenerationStrategy.IdentityByDefaultColumn),
                    user_id = table.Column<Guid>(type: "uuid", nullable: false),
                    public_key = table.Column<byte[]>(type: "bytea", nullable: false),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    revoked_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_user_keys", x => x.id);
                    table.ForeignKey(
                        name: "fk_user_keys_users_user_id",
                        column: x => x.user_id,
                        principalTable: "users",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateIndex(
                name: "ix_users_device_id",
                table: "users",
                column: "device_id",
                unique: true,
                filter: "device_id IS NOT NULL");

            migrationBuilder.CreateIndex(
                name: "ix_app_attest_keys_user_id",
                table: "app_attest_keys",
                column: "user_id");

            migrationBuilder.CreateIndex(
                name: "ix_place_disputes_contributor_id_created_at",
                table: "place_disputes",
                columns: new[] { "contributor_id", "created_at" });

            migrationBuilder.CreateIndex(
                name: "ix_place_salts_resolved_at",
                table: "place_salts",
                column: "resolved_at");

            migrationBuilder.CreateIndex(
                name: "ix_place_votes_place_id_voter_id",
                table: "place_votes",
                columns: new[] { "place_id", "voter_id" },
                unique: true,
                filter: "voter_id IS NOT NULL");

            migrationBuilder.CreateIndex(
                name: "ix_place_votes_voter_id",
                table: "place_votes",
                column: "voter_id");

            migrationBuilder.CreateIndex(
                name: "ix_user_keys_public_key",
                table: "user_keys",
                column: "public_key",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "ix_user_keys_user_id_revoked_at",
                table: "user_keys",
                columns: new[] { "user_id", "revoked_at" });

            // Migration (docs/identity-and-reputation.md): existing users are baharu (default above); existing
            // confirmations become weight-1 votes (still linked while the place is pending, like any pending vote).
            migrationBuilder.Sql("""
                INSERT INTO place_votes (place_id, voter_id, answer, location_correct, weight, created_at, day)
                SELECT place_id, user_id,
                       CASE WHEN exists AND coalesce(correct_location, true) THEN 'Yes' ELSE 'No' END,
                       correct_location, 1, created_at, (created_at AT TIME ZONE 'UTC')::date
                FROM place_verifications;
                UPDATE community_places p SET
                  weighted_yes = coalesce((SELECT sum(weight) FROM place_votes v WHERE v.place_id = p.id AND v.answer = 'Yes'), 0),
                  weighted_no  = coalesce((SELECT sum(weight) FROM place_votes v WHERE v.place_id = p.id AND v.answer = 'No'), 0),
                  resolved_at  = CASE WHEN p.status <> 'Pending' THEN p.updated_at END;
                UPDATE users u SET places_accepted = (SELECT count(*) FROM community_places c WHERE c.owner_id = u.id AND c.status = 'Published');
                """);
            migrationBuilder.DropTable(name: "place_verifications");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "app_attest_keys");

            migrationBuilder.DropTable(
                name: "place_disputes");

            migrationBuilder.DropTable(
                name: "place_salts");

            migrationBuilder.DropTable(
                name: "place_votes");

            migrationBuilder.DropTable(
                name: "user_keys");

            migrationBuilder.DropIndex(
                name: "ix_users_device_id",
                table: "users");

            migrationBuilder.DropColumn(
                name: "last_active_day",
                table: "users");

            migrationBuilder.DropColumn(
                name: "places_accepted",
                table: "users");

            migrationBuilder.DropColumn(
                name: "places_disputed_upheld",
                table: "users");

            migrationBuilder.DropColumn(
                name: "places_rejected",
                table: "users");

            migrationBuilder.DropColumn(
                name: "suspended_until",
                table: "users");

            migrationBuilder.DropColumn(
                name: "tier",
                table: "users");

            migrationBuilder.DropColumn(
                name: "verifier_agreed",
                table: "users");

            migrationBuilder.DropColumn(
                name: "verifier_votes",
                table: "users");

            migrationBuilder.DropColumn(
                name: "votes_day",
                table: "users");

            migrationBuilder.DropColumn(
                name: "votes_today",
                table: "users");

            migrationBuilder.DropColumn(
                name: "audit_open",
                table: "community_places");

            migrationBuilder.DropColumn(
                name: "required_confirmations",
                table: "community_places");

            migrationBuilder.DropColumn(
                name: "resolved_at",
                table: "community_places");

            migrationBuilder.DropColumn(
                name: "rural",
                table: "community_places");

            migrationBuilder.DropColumn(
                name: "weighted_no",
                table: "community_places");

            migrationBuilder.DropColumn(
                name: "weighted_yes",
                table: "community_places");

            migrationBuilder.AlterColumn<string>(
                name: "device_id",
                table: "users",
                type: "character varying(200)",
                maxLength: 200,
                nullable: false,
                defaultValue: "",
                oldClrType: typeof(string),
                oldType: "character varying(200)",
                oldMaxLength: 200,
                oldNullable: true);

            migrationBuilder.CreateTable(
                name: "place_verifications",
                columns: table => new
                {
                    place_id = table.Column<string>(type: "character varying(40)", maxLength: 40, nullable: false),
                    user_id = table.Column<Guid>(type: "uuid", nullable: false),
                    correct_location = table.Column<bool>(type: "boolean", nullable: true),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    exists = table.Column<bool>(type: "boolean", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_place_verifications", x => new { x.place_id, x.user_id });
                    table.ForeignKey(
                        name: "fk_place_verifications_community_places_place_id",
                        column: x => x.place_id,
                        principalTable: "community_places",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                    table.ForeignKey(
                        name: "fk_place_verifications_users_user_id",
                        column: x => x.user_id,
                        principalTable: "users",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateIndex(
                name: "ix_users_device_id",
                table: "users",
                column: "device_id",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "ix_place_verifications_user_id",
                table: "place_verifications",
                column: "user_id");
        }
    }
}
