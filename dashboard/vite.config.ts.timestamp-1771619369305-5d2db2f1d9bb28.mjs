// vite.config.ts
import { defineConfig } from "file:///Users/aelder/Projects/Moneat/dashboard/node_modules/vite/dist/node/index.js";
import react from "file:///Users/aelder/Projects/Moneat/dashboard/node_modules/@vitejs/plugin-react/dist/index.js";
import tailwindcss from "@tailwindcss/vite";
import { TanStackRouterVite } from "file:///Users/aelder/Projects/Moneat/dashboard/node_modules/@tanstack/router-vite-plugin/dist/esm/index.js";
import path from "path";
import { fileURLToPath } from "url";
import sirv from "file:///Users/aelder/Projects/Moneat/dashboard/node_modules/sirv/build.mjs";
var __vite_injected_original_import_meta_url = "file:///Users/aelder/Projects/Moneat/dashboard/vite.config.ts";
var __dirname = path.dirname(fileURLToPath(__vite_injected_original_import_meta_url));
var docsDir = path.join(__dirname, "public", "docs");
var vite_config_default = defineConfig({
  plugins: [
    tailwindcss(),
    react(),
    TanStackRouterVite(),
    {
      name: "serve-docs",
      configureServer(server) {
        const serveDocs = sirv(docsDir, { dev: true });
        server.middlewares.stack.unshift({
          route: "",
          handle: (req, res, next) => {
            if (req.url === "/docs" || req.url?.startsWith("/docs?")) {
              const q = req.url.includes("?") ? req.url.slice(req.url.indexOf("?")) : "";
              res.writeHead(301, { Location: `/docs/${q}` });
              res.end();
              return;
            }
            if (!req.url?.startsWith("/docs/")) return next();
            const originalUrl = req.url;
            req.url = req.url.slice(5) || "/";
            serveDocs(req, res, () => {
              req.url = originalUrl;
              next();
            });
          }
        });
      }
    }
  ],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src")
    }
  },
  server: {
    host: true,
    allowedHosts: true,
    port: 3e3,
    proxy: {
      "/v1": {
        target: "http://localhost:8080",
        changeOrigin: true,
        secure: false,
        cookieDomainRewrite: "localhost",
        configure: (proxy) => {
          proxy.on("proxyRes", (proxyRes, _req, res) => {
            const cookies = proxyRes.headers["set-cookie"];
            if (cookies) {
              res.setHeader("set-cookie", cookies);
            }
          });
        }
      },
      "/auth": {
        target: "http://localhost:8080",
        changeOrigin: true,
        secure: false,
        cookieDomainRewrite: "localhost",
        configure: (proxy) => {
          proxy.on("proxyRes", (proxyRes, _req, res) => {
            const cookies = proxyRes.headers["set-cookie"];
            if (cookies) {
              res.setHeader("set-cookie", cookies);
            }
          });
        }
      },
      "/features": {
        target: "http://localhost:8080",
        changeOrigin: true,
        secure: false
      },
      "/api": {
        target: "http://localhost:8080",
        changeOrigin: true,
        secure: false,
        cookieDomainRewrite: "localhost",
        configure: (proxy) => {
          proxy.on("proxyRes", (proxyRes, _req, res) => {
            const cookies = proxyRes.headers["set-cookie"];
            if (cookies) {
              res.setHeader("set-cookie", cookies);
            }
          });
        }
      }
    }
  }
});
export {
  vite_config_default as default
};
//# sourceMappingURL=data:application/json;base64,ewogICJ2ZXJzaW9uIjogMywKICAic291cmNlcyI6IFsidml0ZS5jb25maWcudHMiXSwKICAic291cmNlc0NvbnRlbnQiOiBbImNvbnN0IF9fdml0ZV9pbmplY3RlZF9vcmlnaW5hbF9kaXJuYW1lID0gXCIvVXNlcnMvYWVsZGVyL1Byb2plY3RzL01vbmVhdC9kYXNoYm9hcmRcIjtjb25zdCBfX3ZpdGVfaW5qZWN0ZWRfb3JpZ2luYWxfZmlsZW5hbWUgPSBcIi9Vc2Vycy9hZWxkZXIvUHJvamVjdHMvTW9uZWF0L2Rhc2hib2FyZC92aXRlLmNvbmZpZy50c1wiO2NvbnN0IF9fdml0ZV9pbmplY3RlZF9vcmlnaW5hbF9pbXBvcnRfbWV0YV91cmwgPSBcImZpbGU6Ly8vVXNlcnMvYWVsZGVyL1Byb2plY3RzL01vbmVhdC9kYXNoYm9hcmQvdml0ZS5jb25maWcudHNcIjtpbXBvcnQge2RlZmluZUNvbmZpZ30gZnJvbSAndml0ZSdcbmltcG9ydCByZWFjdCBmcm9tICdAdml0ZWpzL3BsdWdpbi1yZWFjdCdcbmltcG9ydCB0YWlsd2luZGNzcyBmcm9tICdAdGFpbHdpbmRjc3Mvdml0ZSdcbmltcG9ydCB7VGFuU3RhY2tSb3V0ZXJWaXRlfSBmcm9tICdAdGFuc3RhY2svcm91dGVyLXZpdGUtcGx1Z2luJ1xuaW1wb3J0IHBhdGggZnJvbSAncGF0aCdcbmltcG9ydCB7ZmlsZVVSTFRvUGF0aH0gZnJvbSAndXJsJ1xuaW1wb3J0IHNpcnYgZnJvbSAnc2lydidcbmltcG9ydCB0eXBlIHsgUHJveHlPcHRpb25zIH0gZnJvbSAndml0ZSdcblxuY29uc3QgX19kaXJuYW1lID0gcGF0aC5kaXJuYW1lKGZpbGVVUkxUb1BhdGgoaW1wb3J0Lm1ldGEudXJsKSlcbmNvbnN0IGRvY3NEaXIgPSBwYXRoLmpvaW4oX19kaXJuYW1lLCAncHVibGljJywgJ2RvY3MnKVxuXG5leHBvcnQgZGVmYXVsdCBkZWZpbmVDb25maWcoe1xuICBwbHVnaW5zOiBbXG4gICAgdGFpbHdpbmRjc3MoKSxcbiAgICByZWFjdCgpLFxuICAgIFRhblN0YWNrUm91dGVyVml0ZSgpLFxuICAgIHtcbiAgICAgIG5hbWU6ICdzZXJ2ZS1kb2NzJyxcbiAgICAgIGNvbmZpZ3VyZVNlcnZlcihzZXJ2ZXIpIHtcbiAgICAgICAgY29uc3Qgc2VydmVEb2NzID0gc2lydihkb2NzRGlyLCB7IGRldjogdHJ1ZSB9KVxuICAgICAgICAvLyBSdW4gYmVmb3JlIFNQQSBmYWxsYmFjayBzbyAvZG9jcy8qIHNlcnZlcyBzdGF0aWMgZmlsZXMsIG5vdCBkYXNoYm9hcmQgaW5kZXguaHRtbFxuICAgICAgICBzZXJ2ZXIubWlkZGxld2FyZXMuc3RhY2sudW5zaGlmdCh7XG4gICAgICAgICAgcm91dGU6ICcnLFxuICAgICAgICAgIGhhbmRsZTogKHJlcTogaW1wb3J0KCdodHRwJykuSW5jb21pbmdNZXNzYWdlLCByZXM6IGltcG9ydCgnaHR0cCcpLlNlcnZlclJlc3BvbnNlLCBuZXh0OiAoKSA9PiB2b2lkKSA9PiB7XG4gICAgICAgICAgICBpZiAocmVxLnVybCA9PT0gJy9kb2NzJyB8fCByZXEudXJsPy5zdGFydHNXaXRoKCcvZG9jcz8nKSkge1xuICAgICAgICAgICAgICBjb25zdCBxID0gcmVxLnVybC5pbmNsdWRlcygnPycpID8gcmVxLnVybC5zbGljZShyZXEudXJsLmluZGV4T2YoJz8nKSkgOiAnJ1xuICAgICAgICAgICAgICByZXMud3JpdGVIZWFkKDMwMSwgeyBMb2NhdGlvbjogYC9kb2NzLyR7cX1gIH0pXG4gICAgICAgICAgICAgIHJlcy5lbmQoKVxuICAgICAgICAgICAgICByZXR1cm5cbiAgICAgICAgICAgIH1cbiAgICAgICAgICAgIGlmICghcmVxLnVybD8uc3RhcnRzV2l0aCgnL2RvY3MvJykpIHJldHVybiBuZXh0KClcbiAgICAgICAgICAgIGNvbnN0IG9yaWdpbmFsVXJsID0gcmVxLnVybFxuICAgICAgICAgICAgcmVxLnVybCA9IHJlcS51cmwuc2xpY2UoNSkgfHwgJy8nIC8vIC9kb2NzL2ZvbyAtPiAvZm9vXG4gICAgICAgICAgICBzZXJ2ZURvY3MocmVxLCByZXMsICgpID0+IHtcbiAgICAgICAgICAgICAgcmVxLnVybCA9IG9yaWdpbmFsVXJsXG4gICAgICAgICAgICAgIG5leHQoKVxuICAgICAgICAgICAgfSlcbiAgICAgICAgICB9LFxuICAgICAgICB9KVxuICAgICAgfSxcbiAgICB9LFxuICBdLFxuICByZXNvbHZlOiB7XG4gICAgYWxpYXM6IHtcbiAgICAgIFwiQFwiOiBwYXRoLnJlc29sdmUoX19kaXJuYW1lLCBcIi4vc3JjXCIpLFxuICAgIH0sXG4gIH0sXG4gIHNlcnZlcjoge1xuICAgIGhvc3Q6IHRydWUsXG4gICAgYWxsb3dlZEhvc3RzOiB0cnVlLFxuICAgIHBvcnQ6IDMwMDAsXG4gICAgcHJveHk6IHtcbiAgICAgICcvdjEnOiB7XG4gICAgICAgIHRhcmdldDogJ2h0dHA6Ly9sb2NhbGhvc3Q6ODA4MCcsXG4gICAgICAgIGNoYW5nZU9yaWdpbjogdHJ1ZSxcbiAgICAgICAgc2VjdXJlOiBmYWxzZSxcbiAgICAgICAgY29va2llRG9tYWluUmV3cml0ZTogJ2xvY2FsaG9zdCcsXG4gICAgICAgIGNvbmZpZ3VyZTogKHByb3h5KSA9PiB7XG4gICAgICAgICAgcHJveHkub24oJ3Byb3h5UmVzJywgKHByb3h5UmVzLCBfcmVxLCByZXMpID0+IHtcbiAgICAgICAgICAgIC8vIEVuc3VyZSBjb29raWVzIGFyZSBwYXNzZWQgdGhyb3VnaCBmcm9tIGJhY2tlbmQgdG8gZnJvbnRlbmRcbiAgICAgICAgICAgIGNvbnN0IGNvb2tpZXMgPSBwcm94eVJlcy5oZWFkZXJzWydzZXQtY29va2llJ11cbiAgICAgICAgICAgIGlmIChjb29raWVzKSB7XG4gICAgICAgICAgICAgIHJlcy5zZXRIZWFkZXIoJ3NldC1jb29raWUnLCBjb29raWVzKVxuICAgICAgICAgICAgfVxuICAgICAgICAgIH0pXG4gICAgICAgIH1cbiAgICAgIH0gYXMgUHJveHlPcHRpb25zLFxuICAgICAgJy9hdXRoJzoge1xuICAgICAgICB0YXJnZXQ6ICdodHRwOi8vbG9jYWxob3N0OjgwODAnLFxuICAgICAgICBjaGFuZ2VPcmlnaW46IHRydWUsXG4gICAgICAgIHNlY3VyZTogZmFsc2UsXG4gICAgICAgIGNvb2tpZURvbWFpblJld3JpdGU6ICdsb2NhbGhvc3QnLFxuICAgICAgICBjb25maWd1cmU6IChwcm94eSkgPT4ge1xuICAgICAgICAgIHByb3h5Lm9uKCdwcm94eVJlcycsIChwcm94eVJlcywgX3JlcSwgcmVzKSA9PiB7XG4gICAgICAgICAgICAvLyBFbnN1cmUgY29va2llcyBhcmUgcGFzc2VkIHRocm91Z2ggZnJvbSBiYWNrZW5kIHRvIGZyb250ZW5kXG4gICAgICAgICAgICBjb25zdCBjb29raWVzID0gcHJveHlSZXMuaGVhZGVyc1snc2V0LWNvb2tpZSddXG4gICAgICAgICAgICBpZiAoY29va2llcykge1xuICAgICAgICAgICAgICByZXMuc2V0SGVhZGVyKCdzZXQtY29va2llJywgY29va2llcylcbiAgICAgICAgICAgIH1cbiAgICAgICAgICB9KVxuICAgICAgICB9XG4gICAgICB9IGFzIFByb3h5T3B0aW9ucyxcbiAgICAgICcvZmVhdHVyZXMnOiB7XG4gICAgICAgIHRhcmdldDogJ2h0dHA6Ly9sb2NhbGhvc3Q6ODA4MCcsXG4gICAgICAgIGNoYW5nZU9yaWdpbjogdHJ1ZSxcbiAgICAgICAgc2VjdXJlOiBmYWxzZSxcbiAgICAgIH0gYXMgUHJveHlPcHRpb25zLFxuICAgICAgJy9hcGknOiB7XG4gICAgICAgIHRhcmdldDogJ2h0dHA6Ly9sb2NhbGhvc3Q6ODA4MCcsXG4gICAgICAgIGNoYW5nZU9yaWdpbjogdHJ1ZSxcbiAgICAgICAgc2VjdXJlOiBmYWxzZSxcbiAgICAgICAgY29va2llRG9tYWluUmV3cml0ZTogJ2xvY2FsaG9zdCcsXG4gICAgICAgIGNvbmZpZ3VyZTogKHByb3h5KSA9PiB7XG4gICAgICAgICAgcHJveHkub24oJ3Byb3h5UmVzJywgKHByb3h5UmVzLCBfcmVxLCByZXMpID0+IHtcbiAgICAgICAgICAgIC8vIEVuc3VyZSBjb29raWVzIGFyZSBwYXNzZWQgdGhyb3VnaCBmcm9tIGJhY2tlbmQgdG8gZnJvbnRlbmRcbiAgICAgICAgICAgIGNvbnN0IGNvb2tpZXMgPSBwcm94eVJlcy5oZWFkZXJzWydzZXQtY29va2llJ11cbiAgICAgICAgICAgIGlmIChjb29raWVzKSB7XG4gICAgICAgICAgICAgIHJlcy5zZXRIZWFkZXIoJ3NldC1jb29raWUnLCBjb29raWVzKVxuICAgICAgICAgICAgfVxuICAgICAgICAgIH0pXG4gICAgICAgIH1cbiAgICAgIH0gYXMgUHJveHlPcHRpb25zXG4gICAgfVxuICB9XG59KVxuIl0sCiAgIm1hcHBpbmdzIjogIjtBQUF1UyxTQUFRLG9CQUFtQjtBQUNsVSxPQUFPLFdBQVc7QUFDbEIsT0FBTyxpQkFBaUI7QUFDeEIsU0FBUSwwQkFBeUI7QUFDakMsT0FBTyxVQUFVO0FBQ2pCLFNBQVEscUJBQW9CO0FBQzVCLE9BQU8sVUFBVTtBQU5xSyxJQUFNLDJDQUEyQztBQVN2TyxJQUFNLFlBQVksS0FBSyxRQUFRLGNBQWMsd0NBQWUsQ0FBQztBQUM3RCxJQUFNLFVBQVUsS0FBSyxLQUFLLFdBQVcsVUFBVSxNQUFNO0FBRXJELElBQU8sc0JBQVEsYUFBYTtBQUFBLEVBQzFCLFNBQVM7QUFBQSxJQUNQLFlBQVk7QUFBQSxJQUNaLE1BQU07QUFBQSxJQUNOLG1CQUFtQjtBQUFBLElBQ25CO0FBQUEsTUFDRSxNQUFNO0FBQUEsTUFDTixnQkFBZ0IsUUFBUTtBQUN0QixjQUFNLFlBQVksS0FBSyxTQUFTLEVBQUUsS0FBSyxLQUFLLENBQUM7QUFFN0MsZUFBTyxZQUFZLE1BQU0sUUFBUTtBQUFBLFVBQy9CLE9BQU87QUFBQSxVQUNQLFFBQVEsQ0FBQyxLQUFxQyxLQUFvQyxTQUFxQjtBQUNyRyxnQkFBSSxJQUFJLFFBQVEsV0FBVyxJQUFJLEtBQUssV0FBVyxRQUFRLEdBQUc7QUFDeEQsb0JBQU0sSUFBSSxJQUFJLElBQUksU0FBUyxHQUFHLElBQUksSUFBSSxJQUFJLE1BQU0sSUFBSSxJQUFJLFFBQVEsR0FBRyxDQUFDLElBQUk7QUFDeEUsa0JBQUksVUFBVSxLQUFLLEVBQUUsVUFBVSxTQUFTLENBQUMsR0FBRyxDQUFDO0FBQzdDLGtCQUFJLElBQUk7QUFDUjtBQUFBLFlBQ0Y7QUFDQSxnQkFBSSxDQUFDLElBQUksS0FBSyxXQUFXLFFBQVEsRUFBRyxRQUFPLEtBQUs7QUFDaEQsa0JBQU0sY0FBYyxJQUFJO0FBQ3hCLGdCQUFJLE1BQU0sSUFBSSxJQUFJLE1BQU0sQ0FBQyxLQUFLO0FBQzlCLHNCQUFVLEtBQUssS0FBSyxNQUFNO0FBQ3hCLGtCQUFJLE1BQU07QUFDVixtQkFBSztBQUFBLFlBQ1AsQ0FBQztBQUFBLFVBQ0g7QUFBQSxRQUNGLENBQUM7QUFBQSxNQUNIO0FBQUEsSUFDRjtBQUFBLEVBQ0Y7QUFBQSxFQUNBLFNBQVM7QUFBQSxJQUNQLE9BQU87QUFBQSxNQUNMLEtBQUssS0FBSyxRQUFRLFdBQVcsT0FBTztBQUFBLElBQ3RDO0FBQUEsRUFDRjtBQUFBLEVBQ0EsUUFBUTtBQUFBLElBQ04sTUFBTTtBQUFBLElBQ04sY0FBYztBQUFBLElBQ2QsTUFBTTtBQUFBLElBQ04sT0FBTztBQUFBLE1BQ0wsT0FBTztBQUFBLFFBQ0wsUUFBUTtBQUFBLFFBQ1IsY0FBYztBQUFBLFFBQ2QsUUFBUTtBQUFBLFFBQ1IscUJBQXFCO0FBQUEsUUFDckIsV0FBVyxDQUFDLFVBQVU7QUFDcEIsZ0JBQU0sR0FBRyxZQUFZLENBQUMsVUFBVSxNQUFNLFFBQVE7QUFFNUMsa0JBQU0sVUFBVSxTQUFTLFFBQVEsWUFBWTtBQUM3QyxnQkFBSSxTQUFTO0FBQ1gsa0JBQUksVUFBVSxjQUFjLE9BQU87QUFBQSxZQUNyQztBQUFBLFVBQ0YsQ0FBQztBQUFBLFFBQ0g7QUFBQSxNQUNGO0FBQUEsTUFDQSxTQUFTO0FBQUEsUUFDUCxRQUFRO0FBQUEsUUFDUixjQUFjO0FBQUEsUUFDZCxRQUFRO0FBQUEsUUFDUixxQkFBcUI7QUFBQSxRQUNyQixXQUFXLENBQUMsVUFBVTtBQUNwQixnQkFBTSxHQUFHLFlBQVksQ0FBQyxVQUFVLE1BQU0sUUFBUTtBQUU1QyxrQkFBTSxVQUFVLFNBQVMsUUFBUSxZQUFZO0FBQzdDLGdCQUFJLFNBQVM7QUFDWCxrQkFBSSxVQUFVLGNBQWMsT0FBTztBQUFBLFlBQ3JDO0FBQUEsVUFDRixDQUFDO0FBQUEsUUFDSDtBQUFBLE1BQ0Y7QUFBQSxNQUNBLGFBQWE7QUFBQSxRQUNYLFFBQVE7QUFBQSxRQUNSLGNBQWM7QUFBQSxRQUNkLFFBQVE7QUFBQSxNQUNWO0FBQUEsTUFDQSxRQUFRO0FBQUEsUUFDTixRQUFRO0FBQUEsUUFDUixjQUFjO0FBQUEsUUFDZCxRQUFRO0FBQUEsUUFDUixxQkFBcUI7QUFBQSxRQUNyQixXQUFXLENBQUMsVUFBVTtBQUNwQixnQkFBTSxHQUFHLFlBQVksQ0FBQyxVQUFVLE1BQU0sUUFBUTtBQUU1QyxrQkFBTSxVQUFVLFNBQVMsUUFBUSxZQUFZO0FBQzdDLGdCQUFJLFNBQVM7QUFDWCxrQkFBSSxVQUFVLGNBQWMsT0FBTztBQUFBLFlBQ3JDO0FBQUEsVUFDRixDQUFDO0FBQUEsUUFDSDtBQUFBLE1BQ0Y7QUFBQSxJQUNGO0FBQUEsRUFDRjtBQUNGLENBQUM7IiwKICAibmFtZXMiOiBbXQp9Cg==
