import { defineConfig, loadEnv } from 'vite'
import path from 'path'
import createVitePlugins from './vite/plugins'

export default defineConfig(({ mode, command }) => {
  const env = loadEnv(mode, process.cwd())
  const { VITE_APP_ENV } = env
  // 本机兜底开关：Windows 上系统（Defender 类）会拦掉 esbuild 删除自己的临时文件，
  // 导致 `vite build` 在写产物前就中断（dist 里只剩 public/ 拷来的文件、没有 index.html）。
  // 设 VITE_NO_ESBUILD=1 可跳过 esbuild 的转译与压缩（本项目是纯 JS + Vue，没有 TS/JSX，
  // 跳过转译不影响功能；代价是产物不压缩）。默认不设，行为与原来完全一致。
  const noEsbuild = env.VITE_NO_ESBUILD === '1'
  return {
    base: VITE_APP_ENV === 'production' ? '/' : '/',
    plugins: createVitePlugins(env, command === 'build'),
    esbuild: noEsbuild ? false : undefined,
    build: noEsbuild ? { minify: false } : {},
    resolve: {
      alias: {
        '~': path.resolve(__dirname, './'),
        '@': path.resolve(__dirname, './src')
      },
      extensions: ['.mjs', '.js', '.ts', '.jsx', '.tsx', '.json', '.vue']
    },
    server: {
      port: 5173,
      host: true,
      open: true,
      proxy: {
        '/dev-api': {
          target: 'http://localhost:8090',
          changeOrigin: true,
          rewrite: (p) => p.replace(/^\/dev-api/, ''),
          configure: (proxy) => {
            proxy.on('proxyReq', (proxyReq) => {
              proxyReq.setHeader('Connection', 'keep-alive')
            })
          }
        }
      }
    },
    css: {
      postcss: {
        plugins: [
          {
            postcssPlugin: 'internal:charset-removal',
            AtRule: {
              charset: (atRule) => {
                if (atRule.name === 'charset') {
                  atRule.remove()
                }
              }
            }
          }
        ]
      }
    }
  }
})
