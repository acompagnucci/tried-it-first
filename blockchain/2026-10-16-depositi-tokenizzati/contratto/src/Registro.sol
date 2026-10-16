// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

contract Registro {
    address public immutable GESTORE;
    mapping(uint256 conto => address) public banca;
    mapping(uint256 conto => uint256) public saldo;

    event Bonifico(uint256 indexed da, uint256 indexed a, uint256 centesimi);

    error SoloIlGestore(address chi);
    error NonAutorizzato(address chi, uint256 conto);
    error ContoSconosciuto(uint256 conto);
    error SaldoInsufficiente(uint256 conto);

    constructor() {
        GESTORE = msg.sender;
    }

    function apri(uint256[] calldata conti, address bancaDeiConti, uint256 centesimi) external {
        if (msg.sender != GESTORE) revert SoloIlGestore(msg.sender);
        for (uint256 i = 0; i < conti.length; ++i) {
            banca[conti[i]] = bancaDeiConti;
            saldo[conti[i]] = centesimi;
        }
    }

    function bonifico(uint256 da, uint256 a, uint256 centesimi) external {
        if (banca[da] != msg.sender) revert NonAutorizzato(msg.sender, da);
        if (banca[a] == address(0)) revert ContoSconosciuto(a);
        if (saldo[da] < centesimi) revert SaldoInsufficiente(da);
        saldo[da] -= centesimi;
        saldo[a] += centesimi;
        emit Bonifico(da, a, centesimi);
    }
}
