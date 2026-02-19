// Moneat - observability platform
// Copyright (C) 2026 Moneat
// SPDX-License-Identifier: AGPL-3.0-or-later

import React, {useState} from 'react';
import CodeBlock from '@theme/CodeBlock';
import {sdkSetupData, platformOrder} from '../../data/sdk-setup-data';

export default function SdkSetup() {
  const [selectedPlatform, setSelectedPlatform] = useState('react');
  const platform = sdkSetupData[selectedPlatform] ?? sdkSetupData.other;

  return (
    <div className="margin-top--lg margin-bottom--lg">
      <div className="margin-bottom--md">
        <p className="margin-bottom--sm" style={{fontWeight: 600}}>
          Select your platform
        </p>
        <div
          style={{
            display: 'flex',
            flexWrap: 'wrap',
            gap: '0.5rem',
          }}
        >
          {platformOrder.map((id) => {
            const p = sdkSetupData[id];
            if (!p) return null;
            const isActive = selectedPlatform === id;
            return (
              <button
                key={id}
                type="button"
                onClick={() => setSelectedPlatform(id)}
                style={{
                  padding: '0.4rem 0.75rem',
                  borderRadius: 6,
                  border: isActive ? '2px solid var(--ifm-color-primary)' : '1px solid var(--ifm-color-emphasis-300)',
                  background: isActive ? 'var(--ifm-color-primary-contrast-background)' : 'var(--ifm-background-surface-color)',
                  cursor: 'pointer',
                  fontWeight: isActive ? 600 : 400,
                }}
              >
                {p.sdkName}
              </button>
            );
          })}
        </div>
      </div>

      <div>
        <h3 style={{marginBottom: '1rem'}}>{platform.sdkName}</h3>
        {platform.steps.map((step, idx) => (
          <div key={idx} className="margin-bottom--lg">
            <h4 style={{fontSize: '1rem', marginBottom: '0.5rem'}}>{step.title}</h4>
            <p style={{marginBottom: '0.5rem', opacity: 0.9, fontSize: '0.9rem'}}>
              {step.description}
            </p>
            {step.code && (
              <CodeBlock language={step.language || 'text'} showLineNumbers={false}>
                {step.code}
              </CodeBlock>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
