using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Gimano.Core.Data.Migrations
{
    /// <inheritdoc />
    public partial class ModerationAndBans : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<DateTimeOffset>(
                name: "banned_at",
                table: "users",
                type: "timestamp with time zone",
                nullable: true);

            migrationBuilder.AddColumn<int>(
                name: "flag_count",
                table: "community_places",
                type: "integer",
                nullable: false,
                defaultValue: 0);

            migrationBuilder.CreateTable(
                name: "place_flags",
                columns: table => new
                {
                    place_id = table.Column<string>(type: "character varying(40)", maxLength: 40, nullable: false),
                    user_id = table.Column<Guid>(type: "uuid", nullable: false),
                    reason = table.Column<string>(type: "character varying(20)", maxLength: 20, nullable: false),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_place_flags", x => new { x.place_id, x.user_id });
                    table.ForeignKey(
                        name: "fk_place_flags_community_places_place_id",
                        column: x => x.place_id,
                        principalTable: "community_places",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                    table.ForeignKey(
                        name: "fk_place_flags_users_user_id",
                        column: x => x.user_id,
                        principalTable: "users",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateIndex(
                name: "ix_place_flags_user_id",
                table: "place_flags",
                column: "user_id");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "place_flags");

            migrationBuilder.DropColumn(
                name: "banned_at",
                table: "users");

            migrationBuilder.DropColumn(
                name: "flag_count",
                table: "community_places");
        }
    }
}
