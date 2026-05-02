      *>****************************************************************
      *> Program     : CBACT04P.CBL  (Portable port of CBACT04C)
      *> Application : CardDemo
      *> Type        : BATCH COBOL Program (test oracle)
      *> Function    : Same interest-calculator logic as CBACT04C, but
      *>               uses LINE SEQUENTIAL files only and loads the
      *>               account / xref / disclosure-group reference data
      *>               into WORKING-STORAGE tables at startup.
      *>
      *>               This variant exists ONLY to provide a byte-for-
      *>               byte oracle for the Java port under stock
      *>               GnuCOBOL (no Berkeley DB / VSAM needed).
      *>
      *>               When the env var TEST_FIXED_TS is set to a 26-
      *>               char DB2 timestamp string, that value replaces
      *>               FUNCTION CURRENT-DATE — so the Java run and the
      *>               COBOL run can produce identical timestamps.
      *>****************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CBACT04P.
       AUTHOR. CardDemo-Java-Port.

       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT TCATBAL-FILE ASSIGN TO "tcatbal.dat"
                  ORGANIZATION IS LINE SEQUENTIAL
                  FILE STATUS  IS TCATBALF-STATUS.

           SELECT XREF-FILE    ASSIGN TO "cardxref.dat"
                  ORGANIZATION IS LINE SEQUENTIAL
                  FILE STATUS  IS XREFFILE-STATUS.

           SELECT ACCT-IN-FILE ASSIGN TO "acctdata.dat"
                  ORGANIZATION IS LINE SEQUENTIAL
                  FILE STATUS  IS ACCTIN-STATUS.

           SELECT ACCT-OUT-FILE ASSIGN TO "acctdata.out"
                  ORGANIZATION IS LINE SEQUENTIAL
                  FILE STATUS  IS ACCTOUT-STATUS.

           SELECT DISCGRP-FILE ASSIGN TO "discgrp.dat"
                  ORGANIZATION IS LINE SEQUENTIAL
                  FILE STATUS  IS DISCGRP-STATUS.

           SELECT TRANSACT-FILE ASSIGN TO "transact.dat"
                  ORGANIZATION IS LINE SEQUENTIAL
                  FILE STATUS  IS TRANFILE-STATUS.

       DATA DIVISION.
       FILE SECTION.
       FD  TCATBAL-FILE.
       01  FD-TCB-RAW          PIC X(50).
       FD  XREF-FILE.
       01  FD-XREF-RAW         PIC X(50).
       FD  ACCT-IN-FILE.
       01  FD-ACCTIN-RAW       PIC X(300).
       FD  ACCT-OUT-FILE.
       01  FD-ACCTOUT-RAW      PIC X(300).
       FD  DISCGRP-FILE.
       01  FD-DISCGRP-RAW      PIC X(50).
       FD  TRANSACT-FILE.
       01  FD-TRAN-RAW         PIC X(350).

       WORKING-STORAGE SECTION.

       COPY CVTRA01Y.
       COPY CVACT03Y.
       COPY CVTRA02Y.
       COPY CVACT01Y.
       COPY CVTRA05Y.

       01  TCATBALF-STATUS     PIC XX.
       01  XREFFILE-STATUS     PIC XX.
       01  ACCTIN-STATUS       PIC XX.
       01  ACCTOUT-STATUS      PIC XX.
       01  DISCGRP-STATUS      PIC XX.
       01  TRANFILE-STATUS     PIC XX.

       01  ACCT-TABLE.
           05 ACCT-COUNT       PIC 9(05) VALUE 0.
           05 ACCT-ENTRY OCCURS 1 TO 50000 TIMES
                              DEPENDING ON ACCT-COUNT.
              10 T-ACCT-RAW    PIC X(300).

       01  XREF-TABLE.
           05 XREF-COUNT       PIC 9(05) VALUE 0.
           05 XREF-ENTRY OCCURS 1 TO 50000 TIMES
                              DEPENDING ON XREF-COUNT.
              10 T-XREF-RAW    PIC X(50).

       01  DISCGRP-TABLE.
           05 DISCGRP-COUNT    PIC 9(05) VALUE 0.
           05 DISCGRP-ENTRY OCCURS 1 TO 50000 TIMES
                              DEPENDING ON DISCGRP-COUNT.
              10 T-DG-RAW      PIC X(50).

       01  PROBE-DG-KEY.
           05 PROBE-DG-GROUP   PIC X(10).
           05 PROBE-DG-TYPE    PIC X(02).
           05 PROBE-DG-CAT     PIC 9(04).

       01  WS-WORK.
           05 WS-IDX           PIC 9(05).
           05 WS-CURR-ACCT-IDX PIC 9(05).
           05 WS-FOUND         PIC X VALUE 'N'.

       01  END-OF-FILE         PIC X(01) VALUE 'N'.
       01  ABCODE              PIC S9(9) BINARY.
       01  TIMING              PIC S9(9) BINARY.

       01  COBOL-TS.
           05 COB-YYYY         PIC X(04).
           05 COB-MM           PIC X(02).
           05 COB-DD           PIC X(02).
           05 COB-HH           PIC X(02).
           05 COB-MIN          PIC X(02).
           05 COB-SS           PIC X(02).
           05 COB-MIL          PIC X(02).
           05 COB-REST         PIC X(05).
       01  DB2-FORMAT-TS       PIC X(26).
       01  FILLER REDEFINES DB2-FORMAT-TS.
           06 DB2-YYYY         PIC X(004).
           06 DB2-STREEP-1     PIC X.
           06 DB2-MM           PIC X(002).
           06 DB2-STREEP-2     PIC X.
           06 DB2-DD           PIC X(002).
           06 DB2-STREEP-3     PIC X.
           06 DB2-HH           PIC X(002).
           06 DB2-DOT-1        PIC X.
           06 DB2-MIN          PIC X(002).
           06 DB2-DOT-2        PIC X.
           06 DB2-SS           PIC X(002).
           06 DB2-DOT-3        PIC X.
           06 DB2-MIL          PIC 9(002).
           06 DB2-REST         PIC X(04).

       01  WS-TEST-FIXED-TS    PIC X(26) VALUE SPACES.
       01  WS-USE-FIXED-TS     PIC X     VALUE 'N'.

       01  WS-MISC-VARS.
           05 WS-LAST-ACCT-NUM PIC X(11) VALUE SPACES.
           05 WS-MONTHLY-INT   PIC S9(09)V99 VALUE 0.
           05 WS-TOTAL-INT     PIC S9(09)V99 VALUE 0.
           05 WS-FIRST-TIME    PIC X(01)     VALUE 'Y'.
       01  WS-COUNTERS.
           05 WS-RECORD-COUNT  PIC 9(09) VALUE 0.
           05 WS-TRANID-SUFFIX PIC 9(06) VALUE 0.

       01  WS-PARM-DATE        PIC X(10) VALUE "2025-04-29".

       PROCEDURE DIVISION.

       0000-MAIN.
           DISPLAY "START OF EXECUTION OF PROGRAM CBACT04P".
           ACCEPT WS-PARM-DATE FROM COMMAND-LINE.
           PERFORM 0050-CHECK-FIXED-TS.
           PERFORM 0100-LOAD-ACCT-TABLE.
           PERFORM 0200-LOAD-XREF-TABLE.
           PERFORM 0300-LOAD-DISCGRP-TABLE.
           OPEN INPUT  TCATBAL-FILE.
           OPEN OUTPUT TRANSACT-FILE.

           PERFORM UNTIL END-OF-FILE = 'Y'
               IF END-OF-FILE = 'N'
                   PERFORM 1000-TCATBALF-GET-NEXT
                   IF END-OF-FILE = 'N'
                     ADD 1 TO WS-RECORD-COUNT
                     DISPLAY TRAN-CAT-BAL-RECORD
                     IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM
                       IF WS-FIRST-TIME NOT = 'Y'
                          PERFORM 1050-UPDATE-ACCOUNT
                       ELSE
                          MOVE 'N' TO WS-FIRST-TIME
                       END-IF
                       MOVE 0 TO WS-TOTAL-INT
                       MOVE TRANCAT-ACCT-ID TO WS-LAST-ACCT-NUM
                       PERFORM 1100-GET-ACCT-DATA
                       PERFORM 1110-GET-XREF-DATA
                     END-IF
                     PERFORM 1200-GET-INTEREST-RATE
                     IF DIS-INT-RATE NOT = 0
                       PERFORM 1300-COMPUTE-INTEREST
                       PERFORM 1400-COMPUTE-FEES
                     END-IF
                   END-IF
               ELSE
                    PERFORM 1050-UPDATE-ACCOUNT
               END-IF
           END-PERFORM.

           CLOSE TCATBAL-FILE.
           CLOSE TRANSACT-FILE.
           PERFORM 9500-DUMP-ACCT-TABLE.
           DISPLAY "END OF EXECUTION OF PROGRAM CBACT04P".
           DISPLAY "RECORDS PROCESSED: " WS-RECORD-COUNT.
           GOBACK.

       0050-CHECK-FIXED-TS.
           ACCEPT WS-TEST-FIXED-TS FROM ENVIRONMENT "TEST_FIXED_TS"
              ON EXCEPTION CONTINUE
           END-ACCEPT
           IF WS-TEST-FIXED-TS NOT = SPACES
               MOVE 'Y' TO WS-USE-FIXED-TS
           END-IF.

       0100-LOAD-ACCT-TABLE.
           OPEN INPUT ACCT-IN-FILE
           MOVE '00' TO ACCTIN-STATUS
           PERFORM UNTIL ACCTIN-STATUS = '10'
               READ ACCT-IN-FILE INTO ACCOUNT-RECORD
                   AT END
                       MOVE '10' TO ACCTIN-STATUS
                   NOT AT END
                       ADD 1 TO ACCT-COUNT
                       MOVE ACCOUNT-RECORD
                            TO T-ACCT-RAW(ACCT-COUNT)
               END-READ
           END-PERFORM
           CLOSE ACCT-IN-FILE.

       0200-LOAD-XREF-TABLE.
           OPEN INPUT XREF-FILE
           MOVE '00' TO XREFFILE-STATUS
           PERFORM UNTIL XREFFILE-STATUS = '10'
               READ XREF-FILE INTO CARD-XREF-RECORD
                   AT END
                       MOVE '10' TO XREFFILE-STATUS
                   NOT AT END
                       ADD 1 TO XREF-COUNT
                       MOVE CARD-XREF-RECORD
                            TO T-XREF-RAW(XREF-COUNT)
               END-READ
           END-PERFORM
           CLOSE XREF-FILE.

       0300-LOAD-DISCGRP-TABLE.
           OPEN INPUT DISCGRP-FILE
           MOVE '00' TO DISCGRP-STATUS
           PERFORM UNTIL DISCGRP-STATUS = '10'
               READ DISCGRP-FILE INTO DIS-GROUP-RECORD
                   AT END
                       MOVE '10' TO DISCGRP-STATUS
                   NOT AT END
                       ADD 1 TO DISCGRP-COUNT
                       MOVE DIS-GROUP-RECORD
                            TO T-DG-RAW(DISCGRP-COUNT)
               END-READ
           END-PERFORM
           CLOSE DISCGRP-FILE.

       1000-TCATBALF-GET-NEXT.
           READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD
               AT END
                   MOVE 'Y' TO END-OF-FILE
           END-READ.

       1050-UPDATE-ACCOUNT.
           ADD WS-TOTAL-INT TO ACCT-CURR-BAL
           MOVE 0 TO ACCT-CURR-CYC-CREDIT
           MOVE 0 TO ACCT-CURR-CYC-DEBIT
           MOVE ACCOUNT-RECORD TO T-ACCT-RAW(WS-CURR-ACCT-IDX).

       1100-GET-ACCT-DATA.
           MOVE 'N' TO WS-FOUND
           PERFORM VARYING WS-IDX FROM 1 BY 1
                   UNTIL WS-IDX > ACCT-COUNT OR WS-FOUND = 'Y'
               IF T-ACCT-RAW(WS-IDX)(1:11) = WS-LAST-ACCT-NUM
                   MOVE T-ACCT-RAW(WS-IDX) TO ACCOUNT-RECORD
                   MOVE WS-IDX TO WS-CURR-ACCT-IDX
                   MOVE 'Y' TO WS-FOUND
               END-IF
           END-PERFORM
           IF WS-FOUND = 'N'
               DISPLAY "ACCOUNT NOT FOUND: " WS-LAST-ACCT-NUM
               PERFORM 9999-ABEND-PROGRAM
           END-IF.

       1110-GET-XREF-DATA.
           MOVE 'N' TO WS-FOUND
           PERFORM VARYING WS-IDX FROM 1 BY 1
                   UNTIL WS-IDX > XREF-COUNT OR WS-FOUND = 'Y'
               IF T-XREF-RAW(WS-IDX)(26:11) = WS-LAST-ACCT-NUM
                   MOVE T-XREF-RAW(WS-IDX) TO CARD-XREF-RECORD
                   MOVE 'Y' TO WS-FOUND
               END-IF
           END-PERFORM
           IF WS-FOUND = 'N'
               DISPLAY "XREF NOT FOUND FOR ACCT: " WS-LAST-ACCT-NUM
               PERFORM 9999-ABEND-PROGRAM
           END-IF.

       1200-GET-INTEREST-RATE.
           MOVE ACCT-GROUP-ID  TO PROBE-DG-GROUP
           MOVE TRANCAT-TYPE-CD TO PROBE-DG-TYPE
           MOVE TRANCAT-CD     TO PROBE-DG-CAT
           PERFORM 1200-LOOKUP-DG
           IF WS-FOUND = 'N'
               MOVE "DEFAULT   " TO PROBE-DG-GROUP
               PERFORM 1200-LOOKUP-DG
               IF WS-FOUND = 'N'
                   DISPLAY "ERROR READING DEFAULT DISCLOSURE GROUP"
                   PERFORM 9999-ABEND-PROGRAM
               END-IF
           END-IF.

       1200-LOOKUP-DG.
           MOVE 'N' TO WS-FOUND
           PERFORM VARYING WS-IDX FROM 1 BY 1
                   UNTIL WS-IDX > DISCGRP-COUNT OR WS-FOUND = 'Y'
               IF T-DG-RAW(WS-IDX)(1:16) = PROBE-DG-KEY
                   MOVE T-DG-RAW(WS-IDX) TO DIS-GROUP-RECORD
                   MOVE 'Y' TO WS-FOUND
               END-IF
           END-PERFORM.

       1300-COMPUTE-INTEREST.
           COMPUTE WS-MONTHLY-INT
            = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200
           ADD WS-MONTHLY-INT TO WS-TOTAL-INT
           PERFORM 1300-B-WRITE-TX.

       1300-B-WRITE-TX.
           ADD 1 TO WS-TRANID-SUFFIX
           STRING WS-PARM-DATE,
                  WS-TRANID-SUFFIX
             DELIMITED BY SIZE
             INTO TRAN-ID
           END-STRING
           MOVE "01"                TO TRAN-TYPE-CD
           MOVE "0005"              TO TRAN-CAT-CD
           MOVE "System    "        TO TRAN-SOURCE
           STRING "Int. for a/c " ,
                  ACCT-ID
                  DELIMITED BY SIZE
            INTO TRAN-DESC
           END-STRING
           MOVE WS-MONTHLY-INT      TO TRAN-AMT
           MOVE 0                   TO TRAN-MERCHANT-ID
           MOVE SPACES              TO TRAN-MERCHANT-NAME
           MOVE SPACES              TO TRAN-MERCHANT-CITY
           MOVE SPACES              TO TRAN-MERCHANT-ZIP
           MOVE XREF-CARD-NUM       TO TRAN-CARD-NUM
           PERFORM Z-GET-DB2-FORMAT-TIMESTAMP
           MOVE DB2-FORMAT-TS       TO TRAN-ORIG-TS
           MOVE DB2-FORMAT-TS       TO TRAN-PROC-TS
           WRITE FD-TRAN-RAW FROM TRAN-RECORD.

       1400-COMPUTE-FEES.
      *>     To be implemented.
           CONTINUE.

       9500-DUMP-ACCT-TABLE.
           OPEN OUTPUT ACCT-OUT-FILE
           PERFORM VARYING WS-IDX FROM 1 BY 1
                   UNTIL WS-IDX > ACCT-COUNT
               WRITE FD-ACCTOUT-RAW FROM T-ACCT-RAW(WS-IDX)
           END-PERFORM
           CLOSE ACCT-OUT-FILE.

       Z-GET-DB2-FORMAT-TIMESTAMP.
           IF WS-USE-FIXED-TS = 'Y'
               MOVE WS-TEST-FIXED-TS TO DB2-FORMAT-TS
           ELSE
               MOVE FUNCTION CURRENT-DATE TO COBOL-TS
               MOVE COB-YYYY TO DB2-YYYY
               MOVE COB-MM   TO DB2-MM
               MOVE COB-DD   TO DB2-DD
               MOVE COB-HH   TO DB2-HH
               MOVE COB-MIN  TO DB2-MIN
               MOVE COB-SS   TO DB2-SS
               MOVE COB-MIL  TO DB2-MIL
               MOVE "0000"   TO DB2-REST
               MOVE "-" TO DB2-STREEP-1 DB2-STREEP-2 DB2-STREEP-3
               MOVE "." TO DB2-DOT-1 DB2-DOT-2 DB2-DOT-3
           END-IF.

       9999-ABEND-PROGRAM.
           DISPLAY "ABENDING PROGRAM"
           STOP RUN RETURNING 999.
