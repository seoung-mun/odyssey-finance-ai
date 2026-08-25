const apiOrigin = process.env.API_ORIGIN;

if (!apiOrigin || !/^https:\/\/[^/]+(?::\d+)?$/.test(apiOrigin)) {
  throw new Error("API_ORIGIN must be an HTTPS origin without a trailing slash");
}

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
