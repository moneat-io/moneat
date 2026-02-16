// Moneat - Mobile-First Error Monitoring Platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

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
