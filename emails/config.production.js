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
    // Set locals to use {{ }} placeholders for production output
    locals: {
      page: {
        issueTitle: '{{ issueTitle }}',
        issueLevel: '{{ issueLevel }}',
        issueCulprit: '{{ issueCulprit }}',
        issueMessage: '{{ issueMessage }}',
        issueCount: '{{ issueCount }}',
        issueUrl: '{{ issueUrl }}',
        projectName: '{{ projectName }}',
        environment: '{{ environment }}',
        timestamp: '{{ timestamp }}',
        stackTrace: '{{ stackTrace }}',
        settingsUrl: '{{ settingsUrl }}',
        unsubscribeUrl: '{{ unsubscribeUrl }}',
      },
      year: '{{ year }}',
    },
  },
  css: {
    inline: true,
    purge: true,
  },
  prettify: true,
  minify: {
    collapseWhitespace: true,
    conservativeCollapse: true,
  },
}
