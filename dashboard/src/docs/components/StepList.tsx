import type {ReactNode} from 'react'

interface Step {
  title: string
  content: ReactNode
}

interface StepListProps {
  steps: Step[]
}

export default function StepList({steps}: StepListProps) {
  return (
    <ol className="mb-4 mt-4 list-none pl-0">
      {steps.map((step, index) => (
        <li key={index} className="mb-6 flex gap-4">
          <div className="flex size-7 flex-shrink-0 items-center justify-center rounded-md border border-white/10 bg-white/[0.04] text-sm font-semibold text-slate-200">
            {index + 1}
          </div>
          <div className="flex-1">
            <h4 className="mb-2 font-semibold text-white">{step.title}</h4>
            <div className="text-sm text-slate-300">{step.content}</div>
          </div>
        </li>
      ))}
    </ol>
  )
}
