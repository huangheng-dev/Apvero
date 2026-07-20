import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": "http://localhost:8080",
      "/worker": {
        target: "http://localhost:8090",
        rewrite: (path) => path.replace(/^\/worker/, ""),
      },
    },
  },
});
