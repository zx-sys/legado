import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { createSvgIconsPlugin } from 'vite-plugin-svg-icons'
import VueSetupExtend from 'vite-plugin-vue-setup-extend'
import { resolve } from 'path';

const pathResolve = (dir) => {
    return resolve(__dirname, '.', dir);
};

let alias = {
    '@': pathResolve('./src/'),
}

export default defineConfig({
    plugins: [
        vue(),
        VueSetupExtend(),
        createSvgIconsPlugin({
            iconDirs: [resolve(process.cwd(), 'src/assets/lybbn/icons/svg')],
            symbolId: 'icon-[name]',
        }),
    ],
    base: '/',
    root: './',
    server: {
        https: false,
        host: true,
        port: 8081,
        hmr: true,
        open: false,
        cors: true,
        strictPort: true,
        overlay: false,
    },
    build: {
        target: 'esnext',
        minify: 'esbuild',
        manifest: false,
        sourcemap: false,
        outDir: 'dist',
        emptyOutDir: true,
        assetsDir: 'static',
        chunkSizeWarningLimit: 2000,
        esbuild: {
            drop: ['console', 'debugger'],
        },
        rollupOptions: {
            output: {
                entryFileNames: `static/[name].[hash].js`,
                chunkFileNames: `static/[name].[hash].js`,
                assetFileNames: `static/[name].[hash].[ext]`,
                manualChunks(id) {
                    if (id.includes('node_modules')) {
                        return id.toString().split('node_modules/')[1].split('/')[0].toString();
                    }
                }
            }
        }
    },
    resolve: {
        alias,
    },
    css: {
        preprocessorOptions: {
            scss: {
                api: 'modern-compiler',
            },
        },
    },
})
