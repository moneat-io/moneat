/** @type {import('@maizzle/framework').Config} */
export default {
  build: {
    content: ['src/templates/**/*.html'],
    output: {
      path: 'build/templates/email',
      from: ['src/templates'],
    },
  },
  // Use [[ ]] for Maizzle expressions, leaving {{ }} for backend substitution
  expressions: {
    delimiters: ['[[', ']]'],
    unescapeDelimiters: ['[[[', ']]]'],
    locals: {
      // Shared variables available in all templates
      settingsUrl: 'https://moneat.example.com/settings/notifications',
      unsubscribeUrl: 'https://moneat.example.com/unsubscribe?token=abc123',
      year: new Date().getFullYear(),
    },
  },
}
