const apiOrigin = "https://3-34-97-80.nip.io";

export const config = {
  framework: "vite",
  outputDirectory: "dist",
  rewrites: [
    {
      source: "/api/v1/:path*",
      destination: `${apiOrigin}/api/v1/:path*`,
    },
    {
      source: "/:path((?!api/).*)",
      destination: "/index.html",
    },
  ],
};
