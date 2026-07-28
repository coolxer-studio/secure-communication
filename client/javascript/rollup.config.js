import { babel } from '@rollup/plugin-babel';

function plugins() {
  return [
    babel({
      babelHelpers: 'bundled',
      exclude: 'node_modules/**',
      presets: [
        [
          '@babel/preset-env',
          {
            modules: false,
            targets: {
              ie: '8'
            }
          }
        ]
      ]
    })
  ];
}

const sharedOutput = {
  generatedCode: 'es5',
  sourcemap: true
};

export default [
  {
    input: 'src/index.js',
    output: [
      {
        ...sharedOutput,
        file: 'dist/index.cjs',
        format: 'cjs',
        exports: 'named'
      },
      {
        ...sharedOutput,
        file: 'dist/index.esm.js',
        format: 'es'
      }
    ],
    plugins: plugins()
  },
  {
    input: 'src/index.js',
    output: {
      ...sharedOutput,
      file: 'dist/index.umd.js',
      format: 'umd',
      name: 'SecureCommunicationJS',
      exports: 'named'
    },
    plugins: plugins()
  }
];
