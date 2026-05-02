package com.carddemo.batch.interest;

import com.carddemo.batch.interest.domain.AccountRecord;
import com.carddemo.batch.interest.domain.CardXrefRecord;
import com.carddemo.batch.interest.domain.DisclosureGroupRecord;
import com.carddemo.batch.interest.domain.TransactionCategoryBalanceRecord;
import com.carddemo.batch.interest.domain.TransactionRecord;
import com.carddemo.batch.interest.io.AccountFile;
import com.carddemo.batch.interest.io.CardXrefFile;
import com.carddemo.batch.interest.io.DisclosureGroupFile;
import com.carddemo.batch.interest.io.TcatBalReader;
import com.carddemo.batch.interest.io.TransactionWriter;
import com.carddemo.batch.interest.util.BatchAbendException;
import com.carddemo.batch.interest.util.Db2Timestamp;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class InterestCalculator {

    private static final BigDecimal MONTHS_PER_YEAR_X_PERCENT = new BigDecimal("1200");
    private static final BigDecimal ZERO_SCALE_2 = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);

    private static final String TRAN_TYPE_INTEREST = "01";
    private static final String TRAN_CAT_INTEREST = "0005";
    private static final String TRAN_SOURCE_SYSTEM = "System    ";

    private final Config config;

    public InterestCalculator(Config config) {
        this.config = config;
    }

    public void run() throws IOException {
        System.out.println("START OF EXECUTION OF PROGRAM CBACT04C");

        TcatBalReader tcatBal = TcatBalReader.load(config.tcatBalFile);
        CardXrefFile xref = CardXrefFile.load(config.xrefFile);
        DisclosureGroupFile discgrp = DisclosureGroupFile.load(config.discGrpFile);
        AccountFile accounts = AccountFile.load(config.acctFileIn);

        try (TransactionWriter txWriter = new TransactionWriter(config.transactFileOut)) {

            Long lastAcctId = null;
            AccountRecord currentAccount = null;
            CardXrefRecord currentXref = null;
            BigDecimal accumulatedInterest = ZERO_SCALE_2;
            int tranIdSuffix = 0;
            long recordCount = 0;

            for (TransactionCategoryBalanceRecord tcb : tcatBal) {
                recordCount++;
                System.out.println(rawDisplay(tcb));

                long acctId = tcb.acctId();
                if (lastAcctId == null || acctId != lastAcctId) {
                    if (lastAcctId != null) {
                        // 1050-UPDATE-ACCOUNT for the previous account
                        accounts.update(currentAccount.withInterestApplied(accumulatedInterest));
                    }
                    accumulatedInterest = ZERO_SCALE_2;
                    lastAcctId = acctId;
                    currentAccount = accounts.lookup(acctId);                  // 1100-GET-ACCT-DATA
                    currentXref = xref.lookupByAcctId(acctId);                 // 1110-GET-XREF-DATA
                }

                DisclosureGroupRecord dg = discgrp.lookupWithDefaultFallback( // 1200 / 1200-A
                        currentAccount.groupId(), tcb.tranTypeCd(), tcb.tranCatCd());

                if (dg.interestRate().signum() != 0) {
                    BigDecimal monthlyInterest = computeMonthlyInterest(tcb.balance(), dg.interestRate());
                    accumulatedInterest = accumulatedInterest.add(monthlyInterest);

                    tranIdSuffix++;
                    TransactionRecord txRecord = buildInterestTransaction(
                            currentAccount.acctId(),
                            currentXref.cardNum(),
                            tranIdSuffix,
                            monthlyInterest);
                    txWriter.write(txRecord);
                    // 1400-COMPUTE-FEES is "To be implemented" in source — no-op
                }
            }

            if (lastAcctId != null) {
                // EOF flush — mirrors the ELSE branch in 0000-MAIN that fires
                // 1050-UPDATE-ACCOUNT one final time after END-OF-FILE = 'Y'.
                accounts.update(currentAccount.withInterestApplied(accumulatedInterest));
            }

            System.out.println("Records processed: " + recordCount);
        }

        accounts.flush(config.acctFileOut);

        System.out.println("END OF EXECUTION OF PROGRAM CBACT04C");
    }

    private static BigDecimal computeMonthlyInterest(BigDecimal balance, BigDecimal annualRatePercent) {
        // COBOL: COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200
        // No ROUNDED clause => truncation toward zero on the destination scale of 2.
        return balance.multiply(annualRatePercent)
                .divide(MONTHS_PER_YEAR_X_PERCENT, 2, RoundingMode.DOWN);
    }

    private TransactionRecord buildInterestTransaction(
            long acctId, String cardNum, int suffix, BigDecimal amount) {
        String tranId = config.parmDate + String.format("%06d", suffix);
        String desc = "Int. for a/c " + String.format("%011d", acctId);
        String now = Db2Timestamp.now();
        return TransactionRecord.build(
                tranId,
                TRAN_TYPE_INTEREST,
                TRAN_CAT_INTEREST,
                TRAN_SOURCE_SYSTEM,
                desc,
                amount,
                cardNum,
                now,
                now);
    }

    private static String rawDisplay(TransactionCategoryBalanceRecord r) {
        return new String(r.toBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    public static void main(String[] args) {
        try {
            Config config = Config.fromArgs(args);
            new InterestCalculator(config).run();
        } catch (BatchAbendException abend) {
            System.err.println("ABENDING PROGRAM: " + abend.getMessage());
            System.exit(abend.abendCode());
        } catch (IOException ioe) {
            System.err.println("I/O ERROR: " + ioe.getMessage());
            System.exit(999);
        }
    }

    public record Config(
            String parmDate,
            Path tcatBalFile,
            Path xrefFile,
            Path acctFileIn,
            Path discGrpFile,
            Path acctFileOut,
            Path transactFileOut) {

        public Config {
            if (parmDate == null || parmDate.length() != 10) {
                throw new IllegalArgumentException(
                        "parmDate must be 10 characters, got " + parmDate);
            }
        }

        public static Config fromArgs(String[] args) {
            if (args.length < 1) {
                throw new IllegalArgumentException(
                        "Usage: InterestCalculator <PARM-DATE> "
                                + "[--tcatbal=PATH --xref=PATH --acctin=PATH "
                                + "--discgrp=PATH --acctout=PATH --transact=PATH]");
            }
            String parmDate = args[0];
            Path tcatBal = Paths.get(System.getenv().getOrDefault("TCATBALF", "tcatbal.dat"));
            Path xref = Paths.get(System.getenv().getOrDefault("XREFFILE", "cardxref.dat"));
            Path acctIn = Paths.get(System.getenv().getOrDefault("ACCTFILE", "acctdata.dat"));
            Path discGrp = Paths.get(System.getenv().getOrDefault("DISCGRP", "discgrp.dat"));
            Path acctOut = Paths.get(System.getenv().getOrDefault("ACCTOUT", "acctdata.out"));
            Path transact = Paths.get(System.getenv().getOrDefault("TRANSACT", "transact.dat"));

            for (int i = 1; i < args.length; i++) {
                String arg = args[i];
                if (arg.startsWith("--tcatbal=")) tcatBal = Paths.get(arg.substring(10));
                else if (arg.startsWith("--xref=")) xref = Paths.get(arg.substring(7));
                else if (arg.startsWith("--acctin=")) acctIn = Paths.get(arg.substring(9));
                else if (arg.startsWith("--discgrp=")) discGrp = Paths.get(arg.substring(10));
                else if (arg.startsWith("--acctout=")) acctOut = Paths.get(arg.substring(10));
                else if (arg.startsWith("--transact=")) transact = Paths.get(arg.substring(11));
            }

            return new Config(parmDate, tcatBal, xref, acctIn, discGrp, acctOut, transact);
        }
    }
}
