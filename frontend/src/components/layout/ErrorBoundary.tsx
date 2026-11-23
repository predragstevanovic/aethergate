import { Component, type ErrorInfo, type ReactNode } from 'react'

interface Props {
  children: ReactNode
  label?: string
}

interface State {
  error: Error | null
}

/** Contains a crash to one panel: the rest of the console keeps working. */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null }

  static getDerivedStateFromError(error: Error): State {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error(`[${this.props.label ?? 'view'}]`, error, info.componentStack)
  }

  render() {
    if (!this.state.error) return this.props.children
    return (
      <div role="alert" className="rounded-lg border border-bad/30 bg-bad-soft p-5 text-bad">
        <p className="text-sm font-medium">{this.props.label ?? 'This view'} failed to render.</p>
        <p className="mt-1 text-[13px] opacity-80 font-mono break-words">{this.state.error.message}</p>
        <button
          type="button"
          onClick={() => this.setState({ error: null })}
          className="mt-3 text-[13px] underline underline-offset-4"
        >
          Try again
        </button>
      </div>
    )
  }
}
