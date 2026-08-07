module.exports = {
  root: true,
  env: { browser: true, es2022: true },
  parser: "@typescript-eslint/parser",
  parserOptions: {
    ecmaVersion: "latest",
    sourceType: "module",
    ecmaFeatures: { jsx: true },
  },
  settings: { react: { version: "detect" } },
  plugins: ["@typescript-eslint", "react-hooks", "react-refresh"],
  extends: [
    "eslint:recommended",
    "plugin:@typescript-eslint/recommended",
  ],
  rules: {
    "react-hooks/rules-of-hooks": "error",
    "react-hooks/exhaustive-deps": "off",
    "react-refresh/only-export-components": "off",
    "@typescript-eslint/no-unused-vars": [
      "warn",
      { argsIgnorePattern: "^_", varsIgnorePattern: "^_" },
    ],
    // #2603 (SECURITY): every React Query key must come from `qk` (src/api/queryKeys.ts),
    // which prefixes the session's household onto it. An inline array literal skips that
    // prefix, so two households collide on one cache entry in the process-wide
    // QueryClient — the cross-tenant leak #2603 fixed. Convention alone was not enough:
    // seven inline keys had already accumulated by the time it was found, so this is the
    // mechanical gate. See docs/process/query-cache-scoping.md.
    "no-restricted-syntax": [
      "error",
      {
        selector: "Property[key.name='queryKey'] > ArrayExpression",
        message:
          "Build query keys with qk.* (src/api/queryKeys.ts) — an inline array skips the household scope (#2603).",
      },
      {
        // Argument 0 only — `setQueryData`'s second argument is the DATA, which is
        // legitimately an array literal in tests.
        selector:
          "CallExpression[callee.property.name=/^(get|set)QueryData$/][arguments.0.type='ArrayExpression']",
        message:
          "Build query keys with qk.* (src/api/queryKeys.ts) — an inline array skips the household scope (#2603).",
      },
    ],
  },
  ignorePatterns: ["dist", "node_modules", ".eslintrc.cjs"],
};
