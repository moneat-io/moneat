import type {Root} from 'mdast'
import type {Plugin} from 'unified'
import {visit} from 'unist-util-visit'

interface LabelChild {
  type: string
  data?: {directiveLabel?: boolean}
  children?: Array<{value?: string; children?: Array<{value?: string}>}>
}

interface DirectiveNode {
  type: string
  name?: string
  children?: LabelChild[]
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

        // remark-directive stores :::type[Label Text] as the first child
        // with data.directiveLabel = true. Extract and remove it.
        let title: string | undefined
        if (n.children?.[0]?.data?.directiveLabel) {
          const labelNode = n.children[0]
          title = labelNode.children
            ?.flatMap((c) => {
              if (c.value) return [c.value]
              return c.children?.map((cc) => cc.value ?? '') ?? []
            })
            .join('')
          n.children.splice(0, 1)
        }

        const data = n.data || (n.data = {})
        data.hName = 'Admonition'
        data.hProperties = {
          type: n.name,
          ...(title ? {title} : {}),
        }
      }
    })
  }
}

export default remarkAdmonitions
