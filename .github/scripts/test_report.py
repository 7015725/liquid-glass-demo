"""Read exact Gradle XML directories; fail closed on absent tests."""
import argparse
import json
import os
from pathlib import Path
import xml.etree.ElementTree as ET


REPORTS = {
    'engine': 'engine/build/test-results/testAndroidHostTest',
    'androidApp': 'androidApp/build/test-results/testDebugUnitTest',
}


def inspect_reports(directory):
    result = {'tests': 0, 'passed': 0, 'failures': 0, 'errors': 0, 'skipped': 0,
              'test_ids': [], 'problems': []}
    files = sorted(Path(directory).glob('TEST-*.xml'))
    seen = set()
    if not files:
        result['problems'].append('Missing TEST-*.xml reports')
    for file in files:
        try:
            suite = ET.parse(file).getroot()
            if suite.tag != 'testsuite':
                raise ValueError('Expected a Gradle testsuite root')
            cases = suite.findall('testcase')
            counts = {'tests': len(cases), 'failures': 0, 'errors': 0, 'skipped': 0}
            for case in cases:
                identity = (case.get('classname'), case.get('name'))
                if not all(identity) or identity in seen:
                    result['problems'].append(f'Invalid or duplicate test identity: {identity}')
                seen.add(identity)
                result['test_ids'].append('#'.join(part or '' for part in identity))
                for key, element in [('failures', 'failure'), ('errors', 'error'), ('skipped', 'skipped')]:
                    counts[key] += int(case.find(element) is not None)
            for key, actual in counts.items():
                if int(suite.attrib[key]) != actual:
                    result['problems'].append(f'{file.name}: {key} summary disagrees with testcase elements')
                result[key] += actual
        except (OSError, ET.ParseError, ValueError, KeyError) as error:
            result['problems'].append(f'{file.name}: {error}')
    result['passed'] = result['tests'] - result['failures'] - result['errors'] - result['skipped']
    if not result['tests']:
        result['problems'].append('Zero testcases (NO-SOURCE is not a pass)')
    if result['failures'] or result['errors'] or result['skipped']:
        result['problems'].append('Failed, errored or skipped testcases')
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', type=Path, default=Path.cwd())
    parser.add_argument('--output', type=Path, default=Path('out/unit-test-summary.json'))
    args = parser.parse_args()
    modules = {name: inspect_reports(args.root / directory) for name, directory in REPORTS.items()}
    report = {'modules': modules, 'total': sum(m['tests'] for m in modules.values())}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    lines = ['## Unit test results']
    for name, result in modules.items():
        lines.append(f"- {name}: tests={result['tests']}, passed={result['passed']}, "
                     f"failures={result['failures']}, errors={result['errors']}, skipped={result['skipped']}")
        lines.extend(f"  - ERROR: {problem}" for problem in result['problems'])
    text = '\n'.join(lines) + '\n'
    print(text, end='')
    if summary := os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(summary, 'a', encoding='utf-8') as stream:
            stream.write(text)
    return int(any(result['problems'] for result in modules.values()))


if __name__ == '__main__':
    raise SystemExit(main())
