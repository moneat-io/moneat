// Moneat - observability platform
// Copyright (C) 2026 Moneat
// SPDX-License-Identifier: AGPL-3.0-or-later

import type {ReactNode} from 'react';

interface Step {
  title: string;
  content: ReactNode;
}

interface StepListProps {
  steps: Step[];
}

export default function StepList({steps}: StepListProps) {
  return (
    <ol className="margin-top--md margin-bottom--md" style={{listStyle: 'none', paddingLeft: 0}}>
      {steps.map((step, index) => (
        <li key={index} className="margin-bottom--lg" style={{display: 'flex', gap: '1rem'}}>
          <div
            style={{
              flexShrink: 0,
              width: 28,
              height: 28,
              borderRadius: '50%',
              background: 'var(--ifm-color-primary)',
              color: 'var(--ifm-font-color-base-inverse)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontWeight: 600,
              fontSize: 14,
            }}
          >
            {index + 1}
          </div>
          <div style={{flex: 1}}>
            <h4 style={{marginBottom: '0.5rem', fontWeight: 600}}>{step.title}</h4>
            <div style={{fontSize: '0.9rem', opacity: 0.9}}>{step.content}</div>
          </div>
        </li>
      ))}
    </ol>
  );
}
