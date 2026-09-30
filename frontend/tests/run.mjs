import { build } from 'esbuild';
import { writeFile, unlink } from 'node:fs/promises';
const result = await build({
  entryPoints: ['tests/contract.test.tsx'], bundle: true, write: false,
  platform: 'node', format: 'esm', packages: 'external', jsx: 'automatic',
  define: { 'import.meta.env.VITE_API_BASE_URL': '""' }
});
await writeFile('.contract-test.mjs', result.outputFiles[0].text);
try { await import('../.contract-test.mjs'); } finally { await unlink('.contract-test.mjs'); }
