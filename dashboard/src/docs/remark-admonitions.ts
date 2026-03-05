import type {Root} from 'mdast'
import type {Plugin} from 'unified'
import {visit} from 'unist-util-visit'

interface DirectiveNode {
  type: string
  name?: string
  children?: unknown[]
  data?: {
    hName?: string
    hProperties?: Record<string, string>
  }
  attributes?: Record<string, string>
}

const remarkAdmonitions: Plugin<[], Root> = () => {
  return (tree: Root) => {
    visit(tree, (node: unknown) => {
      const n = node as DirectiveNode
      if (
        n.type === 'containerDirective' ||
        n.type === 'leafDirective'
      ) {
        const validTypes = ['info', 'tip', 'warning', 'caution', 'note', 'danger']
        if (!n.name || !validTypes.includes(n.name)) return

        const data = n.data || (n.data = {})
        data.hName = 'Admonition'
        data.hProperties = {
          type: n.name,
          ...(n.attributes?.title ? {title: n.attributes.title} : {}),
        }
      }
    })
  }
}

export default remarkAdmonitions
