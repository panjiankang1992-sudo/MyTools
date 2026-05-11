#!/bin/bash
# SSH expect-like script for password authentication

HOST="$1"
USER="$2"
PASS="$3"
shift 3
CMD="$@"

expect << EOF
set timeout 30
spawn ssh -o StrictHostKeyChecking=no ${USER}@${HOST} $CMD
expect {
    "password:" {
        send "${PASS}\r"
        expect {
            "password:" {
                send "${PASS}\r"
            }
            "$ " {
            }
            "# " {
            }
        }
    }
    "$ " {
    }
    "# " {
    }
}
expect eof
catch wait result
exit [lindex \$result 3]
EOF
