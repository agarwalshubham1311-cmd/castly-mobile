const { defineConfig } = require('eslint/config');
const expo = require('eslint-config-expo/flat');
module.exports = defineConfig([
  expo,
  { ignores: ['dist/**', 'dist-web/**', '.expo/**', 'artifacts/**'] },
  {
    files: ['tests/*.cjs', 'eslint.config.js'],
    languageOptions: {
      globals: {
        require: 'readonly',
        module: 'readonly',
        process: 'readonly',
        console: 'readonly',
        __dirname: 'readonly',
        URL: 'readonly',
      },
    },
  },
]);
