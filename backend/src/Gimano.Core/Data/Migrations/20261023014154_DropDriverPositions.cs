using System;
using Microsoft.EntityFrameworkCore.Migrations;
using NetTopologySuite.Geometries;

#nullable disable

namespace Gimano.Core.Data.Migrations
{
    /// <inheritdoc />
    public partial class DropDriverPositions : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "driver_positions");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "driver_positions",
                columns: table => new
                {
                    user_id = table.Column<Guid>(type: "uuid", nullable: false),
                    heading = table.Column<double>(type: "double precision", nullable: false),
                    location = table.Column<Point>(type: "geography (point, 4326)", nullable: false),
                    mood = table.Column<string>(type: "text", nullable: false),
                    speed_kmh = table.Column<double>(type: "double precision", nullable: false),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_driver_positions", x => x.user_id);
                });

            migrationBuilder.CreateIndex(
                name: "ix_driver_positions_location",
                table: "driver_positions",
                column: "location")
                .Annotation("Npgsql:IndexMethod", "gist");

            migrationBuilder.CreateIndex(
                name: "ix_driver_positions_updated_at",
                table: "driver_positions",
                column: "updated_at");
        }
    }
}
