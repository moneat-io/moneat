/** @type {import('@maizzle/framework').Config} */
export default {
  build: {
    content: ['src/templates/**/*.html'],
  },
  // Use [[ ]] for Maizzle expressions, leaving {{ }} for backend substitution
  posthtml: {
    expressions: {
      delimiters: ['[[', ']]'],
      unescapeDelimiters: ['[[[', ']]]'],
      // Preview values for local development
      locals: {
        issueTitle: 'TypeError: Cannot read property "user" of undefined',
        issueLevel: 'error',
        issueCulprit: 'app/controllers/users.js in handleRequest',
        issueMessage: 'Cannot read property "user" of undefined at line 42',
        issueCount: '12',
        issueUrl: 'https://moneat.example.com/issues/abc123',
        projectName: 'My App (Production)',
        environment: 'production',
        timestamp: '2026-02-05 16:30:00 UTC',
        stackTrace: `TypeError: Cannot read property "user" of undefined
    at handleRequest (app/controllers/users.js:42:15)
    at processRequest (app/middleware/auth.js:28:10)
    at Layer.handle [as handle_request] (express/lib/router/layer.js:95:5)`,
      },
    },
  },
}
