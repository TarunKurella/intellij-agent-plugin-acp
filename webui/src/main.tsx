import React, { useMemo, useState } from 'react'
import { createRoot } from 'react-dom/client'
import './styles.css'

type Msg = { role: 'user' | 'agent' | 'state'; text: string; time: string }

function now() {
  return new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

function App() {
  const [input, setInput] = useState('')
  const [messages, setMessages] = useState<Msg[]>([
    { role: 'state', text: 'Session initialized · Industrial Editorial Noir', time: now() },
    { role: 'agent', text: 'JCEF React shell is live. Ask me to inspect current file or apply a patch.', time: now() },
  ])

  const canSend = input.trim().length > 0

  const stats = useMemo(() => ({
    runs: messages.filter(m => m.role === 'state' && m.text.toLowerCase().includes('run')).length,
    turns: messages.filter(m => m.role !== 'state').length,
  }), [messages])

  const send = () => {
    if (!canSend) return
    const text = input.trim()
    setMessages(prev => [...prev, { role: 'user', text, time: now() }, { role: 'state', text: 'Run started · streaming', time: now() }])
    setInput('')

    // Kotlin bridge hook (next wiring pass)
    ;(window as any).__INTELLI_BRIDGE_SEND__?.({ type: 'prompt.send', text })
  }

  return (
    <div className="app">
      <aside className="rail">
        <div className="rail-title">SESSIONS</div>
        <button className="rail-item active">◉ s_4e9b IDL</button>
        <button className="rail-item">○ s_12ac RUN</button>
        <button className="rail-item">○ s_0aa1 ERR</button>
        <div className="rail-meta">Turns {stats.turns} · Runs {stats.runs}</div>
      </aside>

      <main className="main">
        <header className="head">
          <div>
            <h1>ACTION SPINE</h1>
            <p>Intent → Tools → Diff → Approval → Outcome</p>
          </div>
          <div className="badge">LIVE</div>
        </header>

        <section className="timeline">
          {messages.map((m, i) => (
            <article key={i} className={`card ${m.role}`}>
              <div className="row">
                <span className="chip">{m.role.toUpperCase()}</span>
                <span className="time">{m.time}</span>
              </div>
              <p>{m.text}</p>
            </article>
          ))}
        </section>

        <footer className="composer">
          <input
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && send()}
            placeholder="State intent clearly. Example: Read current file and apply a safe patch."
          />
          <button disabled={!canSend} onClick={send}>EXECUTE</button>
        </footer>
      </main>
    </div>
  )
}

createRoot(document.getElementById('root')!).render(<App />)
