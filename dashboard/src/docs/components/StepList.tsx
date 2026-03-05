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
    <ol className="mt-4 mb-4 list-none pl-0">
      {steps.map((step, index) => (
        <li key={index} className="mb-6 flex gap-4">
          <div className="flex-shrink-0 w-7 h-7 rounded-full bg-sky-500 text-white flex items-center justify-center font-semibold text-sm">
            {index + 1}
          </div>
          <div className="flex-1">
            <h4 className="mb-2 font-semibold">{step.title}</h4>
            <div className="text-sm opacity-90">{step.content}</div>
          </div>
        </li>
      ))}
    </ol>
  )
}
