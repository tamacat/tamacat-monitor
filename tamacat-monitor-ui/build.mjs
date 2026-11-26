// Builds one ES module bundle (+ CSS) per page into dist/static/js; shared code goes to chunks/.
// tamacat-monitor-admin packages dist/ into the WAR.
import * as esbuild from 'esbuild';
import { rmSync, writeFileSync } from 'node:fs';

const pages = ['login', 'home', 'account', 'users', 'servers', 'problems', 'hosts', 'hostgroups', 'items', 'alerts', 'actions', 'server'];
rmSync('dist', { recursive: true, force: true });

const result = await esbuild.build({
  entryPoints: Object.fromEntries(pages.map((p) => [p, `src/pages/${p}.tsx`])),
  outdir: 'dist/static/js',
  bundle: true,
  splitting: true,
  format: 'esm',
  target: ['es2022'],
  jsx: 'automatic',
  minify: true,
  sourcemap: false,
  legalComments: 'linked',
  chunkNames: 'chunks/[name]-[hash]',
  assetNames: 'assets/[name]-[hash]',
  loader: { '.woff': 'file', '.woff2': 'file', '.png': 'file', '.svg': 'file' },
  define: { 'process.env.NODE_ENV': '"production"' },
  metafile: true,
  logLevel: 'info',
});
writeFileSync('dist/meta.json', JSON.stringify(result.metafile));
