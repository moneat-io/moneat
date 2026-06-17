// Moneat - observability platform
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

import {Landing} from './Landing'
import {LandingNavbar, LandingFooter} from './LandingNavbar'
import {useForceDarkTheme} from './usePublicPageTheme'
import {SeoHead} from '@/components/SeoHead'
import {homeSeo} from '@/lib/seo/routes'

export function LandingPage() {
  // The public home page is dark-first (style-guide).
  useForceDarkTheme()

  return (
    <article className="min-h-screen bg-[#08090f] font-display text-slate-300">
      <SeoHead seo={homeSeo} />

      <LandingNavbar tone="dark" />

      <main>
        <Landing />
      </main>

      <LandingFooter tone="dark" />
    </article>
  )
}
