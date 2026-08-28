import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// 백엔드(Spring, 기본 8080)로 /api 요청을 프록시한다.
// 포트를 바꾸려면 VITE_API_TARGET 환경변수를 쓴다.
const API_TARGET = process.env.VITE_API_TARGET || 'http://localhost:8080';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: API_TARGET,
        changeOrigin: true,
      },
    },
  },
});
