using Microsoft.EntityFrameworkCore.Migrations;
using NetTopologySuite.Geometries;

#nullable disable

namespace Gimano.Core.Data.Migrations
{
    /// <inheritdoc />
    public partial class AdminAreas : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "admin_areas",
                columns: table => new
                {
                    id = table.Column<long>(type: "bigint", nullable: false),
                    name = table.Column<string>(type: "character varying(200)", maxLength: 200, nullable: false),
                    admin_level = table.Column<int>(type: "integer", nullable: false),
                    geom = table.Column<MultiPolygon>(type: "geography (multipolygon, 4326)", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_admin_areas", x => x.id);
                });

            migrationBuilder.CreateIndex(
                name: "ix_admin_areas_geom",
                table: "admin_areas",
                column: "geom")
                .Annotation("Npgsql:IndexMethod", "gist");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "admin_areas");
        }
    }
}
