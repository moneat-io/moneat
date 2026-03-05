import {useState} from 'react'
import {sdkSetupData, platformOrder} from '../data/sdk-setup-data'
import {Prism as SyntaxHighlighter} from 'react-syntax-highlighter'
import {oneDark} from 'react-syntax-highlighter/dist/esm/styles/prism'

export default function SdkSetup() {
  const [selectedPlatform, setSelectedPlatform] = useState('react')
  const platform = sdkSetupData[selectedPlatform] ?? sdkSetupData.other

  return (
    <div className="mt-6 mb-6">
      <div className="mb-4">
        <p className="mb-2 font-semibold">Select your platform</p>
        <div className="flex flex-wrap gap-2">
          {platformOrder.map((id) => {
            const p = sdkSetupData[id]
            if (!p) return null
            const isActive = selectedPlatform === id
            return (
              <button
                key={id}
                type="button"
                onClick={() => setSelectedPlatform(id)}
                className={`px-3 py-1.5 rounded-md text-sm transition-colors ${
                  isActive
                    ? 'bg-sky-500/20 border-2 border-sky-500 font-semibold text-sky-300'
                    : 'bg-slate-800 border border-slate-700 text-slate-300 hover:border-slate-500'
                }`}
              >
                {p.sdkName}
              </button>
            )
          })}
        </div>
      </div>

      <div>
        <h3 className="mb-4 text-lg font-semibold">{platform.sdkName}</h3>
        {platform.steps.map((step, idx) => (
          <div key={idx} className="mb-6">
            <h4 className="text-base mb-1 font-medium">{step.title}</h4>
            <p className="mb-2 text-sm text-slate-400">{step.description}</p>
            {step.code && (
              <SyntaxHighlighter
                language={step.language || 'text'}
                style={oneDark}
                customStyle={{borderRadius: '0.375rem'}}
              >
                {step.code}
              </SyntaxHighlighter>
            )}
          </div>
        ))}
      </div>
    </div>
  )
}
