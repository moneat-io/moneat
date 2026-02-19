import typescript from '@rollup/plugin-typescript';
import terser from '@rollup/plugin-terser';

export default [
  // ESM + CJS builds for npm
  {
    input: 'src/index.ts',
    output: [
      { file: 'dist/analytics.esm.js', format: 'es', sourcemap: true },
      { file: 'dist/analytics.cjs.js', format: 'cjs', sourcemap: true },
    ],
    plugins: [
      typescript({ tsconfig: './tsconfig.json' }),
      terser(),
    ],
  },
  // IIFE build for script tag (served at /js/m.js)
  {
    input: 'src/script.ts',
    output: {
      file: 'dist/m.js',
      format: 'iife',
      sourcemap: false,
    },
    plugins: [
      typescript({ tsconfig: './tsconfig.json', declaration: false }),
      terser({ compress: { passes: 2 }, mangle: true }),
    ],
  },
];
