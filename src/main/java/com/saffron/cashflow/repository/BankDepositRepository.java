package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.BankDeposit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.LocalDate;
import java.util.List;

public interface BankDepositRepository extends JpaRepository<BankDeposit, String> {

    /** Deposits whose bankDate falls within {@code [from, to]}, eagerly loading links. */
    @Query("select distinct d from BankDeposit d left join fetch d.links "
            + "where d.bankDate between :from and :to "
            + "order by d.bankDate desc, d.createdAt desc")
    List<BankDeposit> findByBankDateBetween(LocalDate from, LocalDate to);

    /** All deposits that include a link to the given source row (used to discover deposit
     *  membership for a source row whose linked_date lies outside the bank-date window). */
    @Query("select distinct d from BankDeposit d join fetch d.links l "
            + "where l.linkedDate between :from and :to")
    List<BankDeposit> findByLinkedDateBetween(LocalDate from, LocalDate to);
}
