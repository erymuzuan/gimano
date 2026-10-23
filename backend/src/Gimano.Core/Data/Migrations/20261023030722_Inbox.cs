using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Gimano.Core.Data.Migrations
{
    /// <inheritdoc />
    public partial class Inbox : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<DateTimeOffset>(
                name: "inbox_read_up_to",
                table: "users",
                type: "timestamp with time zone",
                nullable: true);

            migrationBuilder.CreateTable(
                name: "announcements",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    title = table.Column<string>(type: "character varying(200)", maxLength: 200, nullable: false),
                    body = table.Column<string>(type: "character varying(2000)", maxLength: 2000, nullable: false),
                    title_ms = table.Column<string>(type: "character varying(200)", maxLength: 200, nullable: true),
                    body_ms = table.Column<string>(type: "character varying(2000)", maxLength: 2000, nullable: true),
                    url = table.Column<string>(type: "character varying(300)", maxLength: 300, nullable: true),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    expires_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_announcements", x => x.id);
                });

            migrationBuilder.CreateTable(
                name: "inbox_items",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    user_id = table.Column<Guid>(type: "uuid", nullable: false),
                    kind = table.Column<string>(type: "character varying(20)", maxLength: 20, nullable: false),
                    title = table.Column<string>(type: "character varying(200)", maxLength: 200, nullable: false),
                    body = table.Column<string>(type: "character varying(1000)", maxLength: 1000, nullable: false),
                    ref_type = table.Column<string>(type: "character varying(20)", maxLength: 20, nullable: true),
                    ref_id = table.Column<string>(type: "character varying(64)", maxLength: 64, nullable: true),
                    aggregate_key = table.Column<string>(type: "character varying(80)", maxLength: 80, nullable: true),
                    count = table.Column<int>(type: "integer", nullable: false),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_inbox_items", x => x.id);
                    table.ForeignKey(
                        name: "fk_inbox_items_users_user_id",
                        column: x => x.user_id,
                        principalTable: "users",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateIndex(
                name: "ix_announcements_created_at",
                table: "announcements",
                column: "created_at");

            migrationBuilder.CreateIndex(
                name: "ix_inbox_items_created_at",
                table: "inbox_items",
                column: "created_at");

            migrationBuilder.CreateIndex(
                name: "ix_inbox_items_user_id_aggregate_key",
                table: "inbox_items",
                columns: new[] { "user_id", "aggregate_key" },
                unique: true,
                filter: "aggregate_key IS NOT NULL");

            migrationBuilder.CreateIndex(
                name: "ix_inbox_items_user_id_id",
                table: "inbox_items",
                columns: new[] { "user_id", "id" });
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "announcements");

            migrationBuilder.DropTable(
                name: "inbox_items");

            migrationBuilder.DropColumn(
                name: "inbox_read_up_to",
                table: "users");
        }
    }
}
