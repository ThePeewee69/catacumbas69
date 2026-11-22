// ESLint for Catasonarr's static UI (orch.md JS-6, JS-4; decisions 94 and 112), as Catapulsar's.
import js from '@eslint/js';
import jsdoc from 'eslint-plugin-jsdoc';
import globals from 'globals';

export default [
  js.configs.recommended,
  {
    files: ['src/main/resources/static/**/*.js'],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'script',
      globals: { ...globals.browser },
    },
    plugins: { jsdoc },
    settings: { jsdoc: { mode: 'jsdoc' } },
    rules: {
      // JS-1
      'prefer-const': 'error',
      'no-var': 'error',
      // `x == null` (null or undefined) is the one loose comparison allowed (JS-1).
      eqeqeq: ['error', 'always', { null: 'ignore' }],
      // JS-4: every function has a JSDoc block with a summary, @param for each parameter and @returns when it
      // returns a value.
      'jsdoc/require-jsdoc': ['error', { require: { FunctionDeclaration: true } }],
      'jsdoc/require-description': 'error',
      'jsdoc/require-param': 'error',
      'jsdoc/require-param-type': 'error',
      'jsdoc/check-param-names': 'error',
      'jsdoc/require-returns': 'error',
      'jsdoc/require-returns-type': 'error',
      'jsdoc/check-tag-names': 'error',
      'jsdoc/check-types': 'error',
      'jsdoc/no-undefined-types': 'off',
    },
  },
];
