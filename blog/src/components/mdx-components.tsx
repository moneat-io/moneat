import type { MDXComponents } from 'mdx/types'

export function useMDXComponents(components: MDXComponents): MDXComponents {
  return {
    ...components,
    // Ensure tables render nicely in dark theme
    table: (props) => (
      <div className="overflow-x-auto my-6">
        <table {...props} />
      </div>
    ),
    // Styled callout via blockquote
    blockquote: (props) => (
      <blockquote
        className="border-l-4 border-sky-500 pl-4 italic text-slate-400"
        {...props}
      />
    ),
  }
}
