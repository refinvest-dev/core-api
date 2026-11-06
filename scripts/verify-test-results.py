"""Verify fresh Gradle JUnit reports, including opt-in PostgreSQL regression suites."""
import argparse
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


def verify(root: Path, require_postgres: bool) -> dict:
    modules = sorted(set(re.findall(r'"(:[a-z][a-z0-9:-]*)"', (root / 'settings.gradle.kts').read_text(encoding='utf-8'))))
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    required_pg = set()
    actual_pg = set()
    suites = {}
    checked_modules = 0
    for module in modules:
        directory = root.joinpath(*module.strip(':').split(':'))
        sources = list((directory / 'src/test/kotlin').rglob('*.kt'))
        if not sources:
            continue
        reports = list((directory / 'build/test-results/test').glob('TEST-*.xml'))
        if not reports:
            raise ValueError(f'Missing test reports: {module}; run cleanTest test first')
        checked_modules += 1
        for source in sources:
            content = source.read_text(encoding='utf-8')
            package = re.search(r'^package\s+([\w.]+)', content, re.MULTILINE)
            for name in re.findall(r'@EnabledIfEnvironmentVariable\(named\s*=\s*"\w+_TEST_JDBC_URL"[\s\S]*?class\s+(\w+)', content):
                required_pg.add((module, package[1] + '.' + name))
        for report in reports:
            suite = ET.parse(report).getroot()
            key = (module, suite.attrib['name'])
            if key in suites:
                raise ValueError(f'Duplicate test suite: {key}')
            counts = {field: int(suite.attrib.get(field, 0)) for field in totals}
            suites[key] = counts
            for field, count in counts.items():
                totals[field] += count
            if counts['failures'] or counts['errors']:
                raise ValueError(f'Failed test suite: {key}: {counts}')
            if require_postgres and counts['skipped']:
                raise ValueError(f'Skipped test suite: {key}: {counts}')
    if not totals['tests']:
        raise ValueError('No tests executed')
    if require_postgres:
        if not required_pg:
            raise ValueError('No PostgreSQL regression suites discovered')
        for key in sorted(required_pg):
            counts = suites.get(key)
            if not counts or counts['tests'] <= counts['skipped']:
                raise ValueError(f'PostgreSQL regression suite did not execute: {key}')
            actual_pg.add(key)
    return dict(modules=checked_modules, **totals, postgresSuites=len(actual_pg),
        postgresTests=sum(suites[key]['tests'] for key in actual_pg))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument('--require-postgres', action='store_true')
    args = parser.parse_args()
    try:
        print(json.dumps(verify(args.root, args.require_postgres), sort_keys=True))
    except (ValueError, OSError, ET.ParseError) as error:
        print(f'Test report verification failed: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
