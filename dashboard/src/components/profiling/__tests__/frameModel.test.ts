// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {describe, it, expect} from 'vitest'
import {
  type FlameNode,
  normalizeLanguage,
  matchesNamespace,
  classifyFrame,
  annotateSelf,
  collapseFrames,
  collapseRecursion,
  invertFrames,
  detectAppNamespaces,
  filterToNamespaces,
  computeTopFunctions,
  computeDiff,
  topLevelNamespace,
} from '../frameModel'

function node(name: string, value: number, children: FlameNode[] = []): FlameNode {
  return {name, value, children}
}

describe('normalizeLanguage', () => {
  it('maps known runtimes', () => {
    expect(normalizeLanguage('jvm')).toBe('jvm')
    expect(normalizeLanguage('Java')).toBe('jvm')
    expect(normalizeLanguage('kotlin')).toBe('jvm')
    expect(normalizeLanguage('golang')).toBe('go')
    expect(normalizeLanguage('go')).toBe('go')
    expect(normalizeLanguage('python')).toBe('python')
    expect(normalizeLanguage('nodejs')).toBe('nodejs')
    expect(normalizeLanguage(undefined)).toBe('unknown')
  })
})

describe('matchesNamespace', () => {
  it('respects segment boundaries', () => {
    expect(matchesNamespace('com.moneat.Foo.bar', 'com.moneat')).toBe(true)
    expect(matchesNamespace('com.moneat', 'com.moneat')).toBe(true)
    expect(matchesNamespace('com.moneat$Inner', 'com.moneat')).toBe(true)
    expect(matchesNamespace('com.moneaty.Foo', 'com.moneat')).toBe(false)
    expect(matchesNamespace('github.com/acme/x.F', 'github.com/acme')).toBe(true)
  })
})

describe('classifyFrame (jvm)', () => {
  const jvm = (appPrefixes: string[] = []) =>
    ({language: 'jvm' as const, appPrefixes})

  it('detects runtime, library and app', () => {
    expect(classifyFrame('java.lang.Thread.run', jvm())).toBe('runtime')
    expect(
      classifyFrame(
        'kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.run',
        jvm(),
      ),
    ).toBe('runtime')
    expect(classifyFrame('io.netty.channel.Foo.bar', jvm())).toBe('library')
    expect(classifyFrame('kotlinx.serialization.Json.encode', jvm())).toBe('library')
  })

  it('treats unknown frames as app until prefixes are known, then library', () => {
    expect(classifyFrame('com.moneat.svc.Foo.bar', jvm())).toBe('app')
    expect(classifyFrame('com.moneat.svc.Foo.bar', jvm(['com.moneat']))).toBe('app')
    expect(classifyFrame('com.other.Lib.x', jvm(['com.moneat']))).toBe('library')
  })
})

describe('classifyFrame (go)', () => {
  const go = (appPrefixes: string[] = []) => ({language: 'go' as const, appPrefixes})

  it('separates stdlib, vendor and app', () => {
    expect(classifyFrame('runtime.main', go())).toBe('runtime')
    expect(classifyFrame('net/http.(*conn).serve', go())).toBe('runtime')
    expect(classifyFrame('golang.org/x/net/http2.run', go())).toBe('library')
    expect(classifyFrame('github.com/acme/api/h.Handle', go())).toBe('app')
    expect(
      classifyFrame('github.com/gorilla/mux.ServeHTTP', go(['github.com/acme/api'])),
    ).toBe('library')
  })
})

describe('annotateSelf', () => {
  it('computes self as value minus children', () => {
    const [root] = annotateSelf([node('a', 10, [node('b', 4), node('c', 3)])])
    expect(root.self).toBe(3)
    expect(root.children[0].self).toBe(4)
  })
})

describe('collapseFrames', () => {
  const opts = {language: 'jvm' as const, appPrefixes: ['com.app']}
  const hideRuntime = (n: FlameNode) =>
    classifyFrame(n.name, opts) === 'runtime'

  it('grafts app children onto nearest survivor and reattributes self', () => {
    const tree = [
      node('com.app.A.run', 10, [
        node('java.lang.Thread.run', 10, [node('com.app.B.work', 6)]),
      ]),
    ]
    const [a] = collapseFrames(tree, hideRuntime)
    expect(a.name).toBe('com.app.A.run')
    expect(a.children).toHaveLength(1)
    expect(a.children[0].name).toBe('com.app.B.work')
    // The hidden runtime frame's self (4) bubbles up to A.
    const [annotated] = annotateSelf(collapseFrames(tree, hideRuntime))
    expect(annotated.self).toBe(4)
  })

  it('merges same-named siblings after grafting', () => {
    const tree = [
      node('com.app.A.run', 10, [
        node('java.lang.Thread.run', 5, [node('com.app.B.work', 3)]),
        node('jdk.internal.X.y', 5, [node('com.app.B.work', 2)]),
      ]),
    ]
    const [a] = collapseFrames(tree, hideRuntime)
    expect(a.children).toHaveLength(1)
    expect(a.children[0]).toMatchObject({name: 'com.app.B.work', value: 5})
  })
})

describe('collapseRecursion', () => {
  it('folds a self-recursive chain', () => {
    const tree = [node('rec', 10, [node('rec', 8, [node('rec', 5, [node('leaf', 5)])])])]
    const [r] = collapseRecursion(tree)
    expect(r.name).toBe('rec')
    expect(r.children).toHaveLength(1)
    expect(r.children[0].name).toBe('leaf')
  })
})

describe('invertFrames', () => {
  it('roots the tree at the hottest leaves', () => {
    const result = invertFrames([
      node('main', 10, [node('a', 6), node('b', 4)]),
    ])
    expect(result.map((r) => r.name)).toEqual(['a', 'b'])
    expect(result[0]).toMatchObject({name: 'a', value: 6})
    expect(result[0].children[0]).toMatchObject({name: 'main', value: 6})
  })
})

describe('detectAppNamespaces', () => {
  it('ranks the dominant non-runtime namespace first', () => {
    const tree = [
      node('com.moneat.svc.A.run', 10, [
        node('java.lang.Thread.run', 4),
        node('com.moneat.svc.B.x', 3),
      ]),
    ]
    const ns = detectAppNamespaces(tree, 'jvm')
    expect(ns[0].namespace).toBe('com.moneat')
    expect(ns[0].self).toBe(6)
    expect(ns.some((n) => n.namespace.startsWith('java'))).toBe(false)
  })
})

describe('filterToNamespaces', () => {
  it('keeps only paths reaching the namespace', () => {
    const tree = [
      node('java.lang.Thread.run', 10, [node('com.app.A.run', 6)]),
      node('io.netty.X.y', 4),
    ]
    const filtered = filterToNamespaces(tree, ['com.app'])
    expect(filtered).toHaveLength(1)
    expect(filtered[0].children[0].name).toBe('com.app.A.run')
  })
})

describe('computeTopFunctions', () => {
  const tree = [
    node('com.moneat.svc.A.run', 10, [
      node('java.lang.Thread.run', 4),
      node('com.moneat.svc.B.x', 3),
    ]),
  ]

  it('ranks by self and supports app-only scope', () => {
    const all = computeTopFunctions(tree, 10, {
      language: 'jvm',
      appPrefixes: ['com.moneat'],
      scope: 'all',
      sortBy: 'self',
    })
    expect(all[0].name).toBe('java.lang.Thread.run')

    const appOnly = computeTopFunctions(tree, 10, {
      language: 'jvm',
      appPrefixes: ['com.moneat'],
      scope: 'app',
      sortBy: 'self',
    })
    expect(appOnly.some((f) => f.name.startsWith('java'))).toBe(false)
    expect(appOnly[0].name).toBe('com.moneat.svc.A.run')
    expect(appOnly[0].selfPercent).toBeCloseTo(30)
  })
})

describe('computeDiff', () => {
  it('flags hotter functions as regressions and missing ones as improvements', () => {
    const current = [node('f.a', 10, [node('f.b', 4)])]
    const baseline = [node('f.a', 10, [node('f.b', 8)])]
    const diff = computeDiff(current, baseline)
    expect(diff.topRegressions[0].name).toBe('f.a')
    expect(diff.topRegressions[0].deltaPercent).toBeCloseTo(40)
    expect(diff.topImprovements[0].name).toBe('f.b')
  })

  it('reports removed functions as improvements', () => {
    const current = [node('x', 10)]
    const baseline = [node('x', 6), node('y', 4)]
    const diff = computeDiff(current, baseline)
    expect(diff.topImprovements.some((d) => d.name === 'y')).toBe(true)
  })
})

describe('topLevelNamespace', () => {
  it('uses two segments for jvm and the module path for go', () => {
    expect(topLevelNamespace('com.moneat.datadog.Foo.bar', 'jvm')).toBe('com.moneat')
    expect(topLevelNamespace('github.com/acme/api/h.Handle', 'go')).toBe(
      'github.com/acme/api',
    )
  })
})
