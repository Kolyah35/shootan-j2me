
def processChars(line: str) -> int:
    splited = line.split(' ')

    for s in splited:
        if s.startswith('chars'): continue
        if s.startswith('count='):
            count = s.split('=')[1]
            return int(count)
    return 0

def processChar(line: str) -> dict:
    splited = line.split(' ')
    ret = dict()

    for s in splited:
        if s.startswith('id='):
            val = s.split('=')[1]
            ret['id'] = int(val)
        if s.startswith('x='):
            val = s.split('=')[1]
            ret['x'] = int(val)
        if s.startswith('y='):
            val = s.split('=')[1]
            ret['y'] = int(val)
        if s.startswith('width='):
            val = s.split('=')[1]
            ret['width'] = int(val)
        if s.startswith('height='):
            val = s.split('=')[1]
            ret['height'] = int(val)
            print(val)
        if s.startswith('xoffset='):
            val = s.split('=')[1]
            ret['xoffset'] = int(val)
        if s.startswith('yoffset='):
            val = s.split('=')[1]
            ret['yoffset'] = int(val)
        if s.startswith('xadvance='):
            val = s.split('=')[1]
            ret['xadvance'] = int(val)
    return ret

def processCommon(line: str) -> int:
    splited = line.split(' ')
    
    for s in splited:
        if s.startswith('lineHeight='):
            val = s.split('=')[1]
            return int(val)

with open('res/font.fnt') as file:
    charscount = 0
    lineHeight = 0
    chars = list()
    for line in file:
        line = line.rstrip()

        if line.startswith('info'): continue
        if line.startswith('page'): continue
        if line.startswith('common'): lineHeight = processCommon(line)
        if line.startswith('chars'): charscount = processChars(line)
        if line.startswith('char '): chars.append(processChar(line))
    
    output = open('res/font.bin', 'wb')
    output.write(charscount.to_bytes(length=2, signed=False))
    output.write(lineHeight.to_bytes(length=1, signed=False))

    for char in chars:
        output.write(char['id'].to_bytes(length=2, signed=False))
        output.write(char['x'].to_bytes(length=1, signed=False))
        output.write(char['y'].to_bytes(length=1, signed=False))
        output.write(char['width'].to_bytes(length=1, signed=False))
        output.write(char['height'].to_bytes(length=1, signed=False))
        output.write(char['xoffset'].to_bytes(length=1, signed=True))
        output.write(char['yoffset'].to_bytes(length=1, signed=True))
        output.write(char['xadvance'].to_bytes(length=1, signed=True))