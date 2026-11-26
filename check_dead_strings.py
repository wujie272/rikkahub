import subprocess, os, re, sys

# Get all string names from English strings.xml
with open('app/src/main/res/values/strings.xml', 'r') as f:
    content = f.read()

string_names = re.findall(r'name="([^"]+)"', content)
print(f'Total strings defined: {len(string_names)}')

# Search for each string name in Kotlin/Java source files
source_dirs = ['app/src/main/java', 'ai/src/main/java']
dead_strings = []
used_strings = []

for name in string_names:
    found = False
    for d in source_dirs:
        if os.path.isdir(d):
            result = subprocess.run(
                ['grep', '-rl', name, d],
                capture_output=True, text=True, timeout=30
            )
            if result.stdout.strip():
                found = True
                break
    if found:
        used_strings.append(name)
    else:
        dead_strings.append(name)

print(f'\nUsed strings: {len(used_strings)}')
print(f'Dead strings: {len(dead_strings)}')
if dead_strings:
    print('\n=== DEAD STRINGS ===')
    for s in sorted(dead_strings):
        print(f'  {s}')
else:
    print('No dead strings found!')
