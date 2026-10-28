import { readFileSync } from 'node:fs'

import { describe, expect, it } from 'vitest'

const stylesheet = readFileSync(new URL('./style.css', import.meta.url), 'utf8')

describe('responsive application shell styles', () => {
  it('lets unbroken descendant content wrap before the root clips overflow', () => {
    expect(stylesheet).toMatch(
      /\.app-root\s*\{[^}]*overflow-wrap:\s*anywhere/s,
    )
    expect(stylesheet).toMatch(
      /\.app-root\s+\*\s*\{[^}]*min-width:\s*0/s,
    )
  })

  it('lets all three navigation columns shrink inside a 320px viewport', () => {
    const phoneRules = stylesheet.slice(
      stylesheet.indexOf('@media (max-width: 540px)'),
    )

    expect(phoneRules).toMatch(
      /\.app-navigation\s*\{[^}]*grid-template-columns:\s*repeat\(3,\s*minmax\(0,\s*1fr\)\)/s,
    )
  })

  it('uses the high-contrast soft vermilion focus ring in the masthead', () => {
    expect(stylesheet).toMatch(
      /\.app-masthead a:focus-visible,\s*\.app-masthead button:focus-visible\s*\{[^}]*outline-color:\s*var\(--vermilion-soft\)/s,
    )
  })
})
