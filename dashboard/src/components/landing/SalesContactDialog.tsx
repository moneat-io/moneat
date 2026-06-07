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

import {useState} from 'react'
import {useMutation} from '@tanstack/react-query'
import {CheckCircle2} from 'lucide-react'
import {api} from '@/lib/api'
import {useToast} from '@/hooks/useToast'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {Textarea} from '@/components/ui/textarea'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'

const EMAIL_REGEX = /^[^@\s]+@[^@\s]+\.[^@\s]+$/

interface FormState {
  name: string
  email: string
  company: string
  message: string
  website: string
}

type FieldErrors = Partial<Record<'name' | 'email' | 'company' | 'message', string>>

const EMPTY_FORM: FormState = {name: '', email: '', company: '', message: '', website: ''}

function validate(form: FormState): FieldErrors {
  const errors: FieldErrors = {}
  if (!form.name.trim()) errors.name = 'Name is required'
  if (!form.email.trim()) {
    errors.email = 'Work email is required'
  } else if (!EMAIL_REGEX.test(form.email.trim())) {
    errors.email = 'Enter a valid work email'
  }
  if (!form.company.trim()) errors.company = 'Company is required'
  if (!form.message.trim()) errors.message = 'A short message is required'
  return errors
}

export function SalesContactDialog({
  open,
  onOpenChange,
}: {
  readonly open: boolean
  readonly onOpenChange: (open: boolean) => void
}) {
  const {toast} = useToast()
  const [form, setForm] = useState<FormState>(EMPTY_FORM)
  const [errors, setErrors] = useState<FieldErrors>({})
  const [submitted, setSubmitted] = useState(false)

  const mutation = useMutation({
    mutationFn: () =>
      api.createSalesInquiry({
        name: form.name.trim(),
        email: form.email.trim(),
        company: form.company.trim(),
        message: form.message.trim(),
        website: form.website,
      }),
    onSuccess: () => setSubmitted(true),
    onError: (err: Error) => {
      toast({
        title: 'Could not send your message',
        description: err.message || 'Please try again in a moment.',
        variant: 'destructive',
      })
    },
  })

  const update = (field: keyof FormState) => (value: string) =>
    setForm((prev) => ({...prev, [field]: value}))

  const handleSubmit = (event: React.SubmitEvent<HTMLFormElement>) => {
    event.preventDefault()
    const nextErrors = validate(form)
    setErrors(nextErrors)
    if (Object.keys(nextErrors).length > 0) return
    mutation.mutate()
  }

  const handleOpenChange = (next: boolean) => {
    if (!next) {
      // Reset for the next time the dialog is opened.
      setForm(EMPTY_FORM)
      setErrors({})
      setSubmitted(false)
      mutation.reset()
    }
    onOpenChange(next)
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="sm:max-w-lg">
        {submitted ? (
          <div className="flex flex-col items-center gap-3 py-6 text-center">
            <CheckCircle2 className="size-10 text-emerald-400" />
            <DialogTitle className="text-lg">Thanks — we'll be in touch</DialogTitle>
            <DialogDescription>
              Our sales team will reach out shortly to talk through your Enterprise requirements.
            </DialogDescription>
            <Button className="mt-2" onClick={() => handleOpenChange(false)}>
              Close
            </Button>
          </div>
        ) : (
          <form onSubmit={handleSubmit} noValidate>
            <DialogHeader>
              <DialogTitle>Talk to sales</DialogTitle>
              <DialogDescription>
                Tell us about your team and we'll put together an Enterprise plan that fits.
              </DialogDescription>
            </DialogHeader>

            <div className="mt-4 space-y-4">
              <div className="space-y-1.5">
                <Label htmlFor="sales-name">Name</Label>
                <Input
                  id="sales-name"
                  value={form.name}
                  onChange={(e) => update('name')(e.target.value)}
                  aria-invalid={Boolean(errors.name)}
                />
                {errors.name ? <p className="text-xs text-rose-400">{errors.name}</p> : null}
              </div>

              <div className="space-y-1.5">
                <Label htmlFor="sales-email">Work email</Label>
                <Input
                  id="sales-email"
                  type="email"
                  value={form.email}
                  onChange={(e) => update('email')(e.target.value)}
                  aria-invalid={Boolean(errors.email)}
                />
                {errors.email ? <p className="text-xs text-rose-400">{errors.email}</p> : null}
              </div>

              <div className="space-y-1.5">
                <Label htmlFor="sales-company">Company</Label>
                <Input
                  id="sales-company"
                  value={form.company}
                  onChange={(e) => update('company')(e.target.value)}
                  aria-invalid={Boolean(errors.company)}
                />
                {errors.company ? <p className="text-xs text-rose-400">{errors.company}</p> : null}
              </div>

              <div className="space-y-1.5">
                <Label htmlFor="sales-message">Message</Label>
                <Textarea
                  id="sales-message"
                  rows={4}
                  value={form.message}
                  onChange={(e) => update('message')(e.target.value)}
                  placeholder="What are you looking to monitor, expected volume, timeline…"
                  aria-invalid={Boolean(errors.message)}
                />
                {errors.message ? <p className="text-xs text-rose-400">{errors.message}</p> : null}
              </div>

              {/* Honeypot: hidden from real users; bots that fill it are silently dropped. */}
              <div aria-hidden className="hidden">
                <label htmlFor="sales-website">Website</label>
                <input
                  id="sales-website"
                  type="text"
                  tabIndex={-1}
                  autoComplete="off"
                  value={form.website}
                  onChange={(e) => update('website')(e.target.value)}
                />
              </div>
            </div>

            <DialogFooter className="mt-6">
              <Button type="submit" disabled={mutation.isPending}>
                {mutation.isPending ? 'Sending…' : 'Send message'}
              </Button>
            </DialogFooter>
          </form>
        )}
      </DialogContent>
    </Dialog>
  )
}
