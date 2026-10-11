// #2906: the SPA's user-visible copy never says "check in", "check out", "checked in" or
// "checked out". Shared devices use sessions ("Start session", "End session", "Available"); the
// internal names (`check_in` rows, the `check-in` / `check-out` routes, the `CheckedOut` reason)
// stay as they are. The mapping is the glossary in docs/design/shared-devices.md.
//
// The scan parses every non-test source file under src/ and reads only the text a person can see:
// string literals, template-literal text, and JSX text. Comments are not part of that, so they are
// not checked. A literal that is all lowercase with no whitespace is a code token (an action name
// like 'check-in', a route, a test id) and is skipped; anything else is treated as copy.
import ts from 'typescript'

const FORBIDDEN = /\bcheck(?:ed)?[\s-]+(?:in|out)\b/i

function isCodeToken(text: string): boolean {
  return !/\s/.test(text) && text === text.toLowerCase()
}

export function findCheckInOutCopy(source: string, fileName: string): string[] {
  const sf = ts.createSourceFile(fileName, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX)
  const hits: string[] = []
  const visit = (node: ts.Node) => {
    let text: string | null = null
    if (ts.isJsxText(node)) text = node.text
    else if (
      ts.isStringLiteral(node) ||
      ts.isNoSubstitutionTemplateLiteral(node) ||
      ts.isTemplateHead(node) ||
      ts.isTemplateMiddle(node) ||
      ts.isTemplateTail(node)
    ) {
      text = isCodeToken(node.text) ? null : node.text
    }
    if (text !== null && FORBIDDEN.test(text)) {
      const { line } = sf.getLineAndCharacterOfPosition(node.getStart(sf))
      hits.push(`${fileName}:${line + 1}: ${text.trim()}`)
    }
    ts.forEachChild(node, visit)
  }
  visit(sf)
  return hits
}

const sources = import.meta.glob<string>('./**/*.{ts,tsx}', { query: '?raw', import: 'default', eager: true })
const appSources = Object.entries(sources).filter(
  ([path]) => !/\.test\.tsx?$/.test(path) && !path.startsWith('./test/'),
)

describe('user-visible copy says "session", never "check in / check out" (#2906)', () => {
  it('the scan sees the SPA (a vacuous glob would pass every check below)', () => {
    expect(appSources.map(([p]) => p)).toEqual(
      expect.arrayContaining(['./components/SharedDevicesCard.tsx', './pages/BlockedPage.tsx']),
    )
  })

  it('the scan catches each phrase in each place copy can live', () => {
    const fixture = [
      "const a = 'Check in'",
      'const b = "Someone has it checked out."',
      'const c = `${name} checked in`',
      'const d = <button>Check out</button>',
      "const e = 'Force check-out'",
    ].join('\n')
    expect(findCheckInOutCopy(fixture, 'fixture.tsx')).toHaveLength(5)
  })

  it('the scan ignores comments and lowercase code tokens', () => {
    const fixture = [
      '// a check-in is opened by POST /check-in',
      "const action = 'check-in'",
      'const path = `/shared-devices/${mac}/check-out`',
      "const testId = 'shared-device-checkin'",
    ].join('\n')
    expect(findCheckInOutCopy(fixture, 'fixture.tsx')).toEqual([])
  })

  it('no SPA source file shows the words to a person', () => {
    const hits = appSources.flatMap(([path, src]) => findCheckInOutCopy(src, path))
    expect(hits).toEqual([])
  })
})
