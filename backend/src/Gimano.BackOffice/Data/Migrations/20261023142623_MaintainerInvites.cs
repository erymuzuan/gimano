using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Gimano.BackOffice.Data.Migrations
{
    /// <inheritdoc />
    public partial class MaintainerInvites : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "MaintainerInvites",
                schema: "backoffice",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    NormalizedEmail = table.Column<string>(type: "character varying(256)", maxLength: 256, nullable: false),
                    TokenHash = table.Column<string>(type: "character varying(64)", maxLength: 64, nullable: false),
                    CreatedAt = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    ExpiresAt = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    UsedAt = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_MaintainerInvites", x => x.Id);
                });

            migrationBuilder.CreateIndex(
                name: "IX_MaintainerInvites_NormalizedEmail",
                schema: "backoffice",
                table: "MaintainerInvites",
                column: "NormalizedEmail");

            migrationBuilder.CreateIndex(
                name: "IX_MaintainerInvites_TokenHash",
                schema: "backoffice",
                table: "MaintainerInvites",
                column: "TokenHash",
                unique: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "MaintainerInvites",
                schema: "backoffice");
        }
    }
}
